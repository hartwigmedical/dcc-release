/*
 * Copyright (c) 2015 The Ontario Institute for Cancer Research. All rights reserved.                             
 *                                                                                                               
 * This program and the accompanying materials are made available under the terms of the GNU Public License v3.0.
 * You should have received a copy of the GNU General Public License along with                                  
 * this program. If not, see <http://www.gnu.org/licenses/>.                                                     
 *                                                                                                               
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY                           
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES                          
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT                           
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,                                
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED                          
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;                               
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER                              
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN                         
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.icgc.dcc.release.job.join.task;

import static com.google.common.base.Preconditions.checkState;
import static org.icgc.dcc.common.core.model.FieldNames.LoaderFieldNames.CONSEQUENCE_ARRAY_NAME;
import static org.icgc.dcc.common.core.model.FieldNames.LoaderFieldNames.SURROGATE_MATCHED_SAMPLE_ID;
import static org.icgc.dcc.common.core.model.FieldNames.NormalizerFieldNames.NORMALIZER_OBSERVATION_ID;
import static org.icgc.dcc.common.core.model.FieldNames.SubmissionFieldNames.SUBMISSION_ANALYZED_SAMPLE_ID;
import static org.icgc.dcc.common.core.model.FieldNames.SubmissionFieldNames.SUBMISSION_MATCHED_SAMPLE_ID;
import static org.icgc.dcc.common.core.model.FieldNames.SubmissionFieldNames.SUBMISSION_OBSERVATION_ANALYSIS_ID;
import static org.icgc.dcc.release.core.util.FieldNames.JoinFieldNames.MUTATION_ID;
import static org.icgc.dcc.release.core.util.FieldNames.JoinFieldNames.PLACEMENT;
import static org.icgc.dcc.release.core.util.FieldNames.JoinFieldNames.SV_ID;
import static org.icgc.dcc.release.core.util.JavaRDDs.getPartitionsCount;
import static org.icgc.dcc.release.core.util.ObjectNodes.textValue;
import static org.icgc.dcc.release.job.join.utils.CombineFunctions.combineConsequences;
import static org.icgc.dcc.release.job.join.utils.Tasks.getSampleSurrogateSampleIds;

import java.util.Collection;
import java.util.Map;

import lombok.val;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.broadcast.Broadcast;
import org.icgc.dcc.release.core.function.KeyFields;
import org.icgc.dcc.release.core.job.FileType;
import org.icgc.dcc.release.core.task.TaskContext;
import org.icgc.dcc.release.job.join.function.AggregateConsequences;
import org.icgc.dcc.release.job.join.model.DonorSample;

import scala.Tuple2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

public class SecondaryJoinTask extends PrimaryMetaJoinTask {

  /**
   * Constants.
   */
  private static final String SECONDARY_FILE_TYPE_SUFFIX = "_S";
  private static final Map<FileType, String[]> SECONDARY_JOIN_FIELDS = ImmutableMap.of(
      FileType.CNSM_P, new String[] { SUBMISSION_OBSERVATION_ANALYSIS_ID, SUBMISSION_ANALYZED_SAMPLE_ID, MUTATION_ID },
      FileType.SGV_P_MASKED, new String[] { NORMALIZER_OBSERVATION_ID },
      FileType.STSM_P, new String[] { SUBMISSION_OBSERVATION_ANALYSIS_ID, SUBMISSION_ANALYZED_SAMPLE_ID, SV_ID,
          PLACEMENT }
      );

  /**
   * Dependencies.
   */
  private final Broadcast<Map<String, Map<String, String>>> sampleSurrogateSampleIdsByProject;

  public SecondaryJoinTask(
      Broadcast<Map<String, Map<String, DonorSample>>> donorSamplesbyProject,
      Broadcast<Map<String, Map<String, String>>> sampleSurrogateSampleIdsByProject,
      FileType primaryFileType)
  {
    super(donorSamplesbyProject, primaryFileType);
    this.sampleSurrogateSampleIdsByProject = sampleSurrogateSampleIdsByProject;
  }

  @Override
  public void execute(TaskContext taskContext) {
    val primaryMeta = joinPrimaryMeta(taskContext);
    val secondaryFileType = resolveSecondaryFileType(primaryFileType);
    val sampleSurrogageSampleIds = getSampleSurrogateSampleIds(taskContext, sampleSurrogateSampleIdsByProject);

    val output = joinSecondary(primaryMeta, secondaryFileType, taskContext)
        .map(addSurrogateMatchingId(sampleSurrogageSampleIds));
    writeOutput(taskContext, output, resolveOutputFileType(primaryFileType));
  }

  public static Function<ObjectNode, ObjectNode> addSurrogateMatchingId(Map<String, String> sampleSurrogageSampleIds) {
    return occurrence -> {
      String matchedSampleId = textValue(occurrence, SUBMISSION_MATCHED_SAMPLE_ID);
      if (matchedSampleId != null) {
        occurrence.put(SURROGATE_MATCHED_SAMPLE_ID, sampleSurrogageSampleIds.get(matchedSampleId));
      }

      return occurrence;
    };
  }

  private static FileType resolveSecondaryFileType(FileType primaryFileType) {
    return resolveFileType(primaryFileType, SECONDARY_FILE_TYPE_SUFFIX);
  }

  private JavaRDD<ObjectNode> joinSecondary(JavaRDD<ObjectNode> primaryMeta, FileType secondaryFileType,
      TaskContext taskContext) {
    val keyFunction = new KeyFields(getSecondaryJoinKeys(primaryFileType));
    val startValue = Sets.<ObjectNode> newHashSet();

    val occurrencePairs = primaryMeta.mapToPair(keyFunction);
    val occurrencePartitions = getPartitionsCount(occurrencePairs);

    val consequences = parseSecondary(secondaryFileType, taskContext)
        .mapToPair(keyFunction)
        .aggregateByKey(startValue, occurrencePartitions, new AggregateConsequences(), combineConsequences());

    return occurrencePairs
        .leftOuterJoin(consequences)
        .map(SecondaryJoinTask::joinConsequences);
  }

  private static ObjectNode joinConsequences(Tuple2<String, Tuple2<ObjectNode, Optional<Collection<ObjectNode>>>> tuple) {
    val primary = tuple._2._1;
    val consequences = tuple._2._2;
    val consequenceArray = primary.withArray(CONSEQUENCE_ARRAY_NAME);
    if (consequences.isPresent()) {
      consequenceArray.addAll(consequences.get());
    }

    return primary;
  }

  private JavaRDD<ObjectNode> parseSecondary(FileType secondaryFileType, TaskContext taskContext) {
    return readInput(taskContext, secondaryFileType);
  }

  private static String[] getSecondaryJoinKeys(FileType fileType) {
    String[] joinKeys = SECONDARY_JOIN_FIELDS.get(fileType);
    checkState(joinKeys != null, "Failed to resolve secondary join keys for type %s", fileType.getId());

    return joinKeys;
  }

}
