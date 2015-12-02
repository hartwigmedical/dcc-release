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
import static org.icgc.dcc.common.core.model.FieldNames.NormalizerFieldNames.NORMALIZER_OBSERVATION_ID;
import static org.icgc.dcc.release.job.join.utils.Tasks.getSampleSurrogateSampleIds;
import static org.icgc.dcc.release.job.join.utils.Tasks.resolveDonorSamples;

import java.util.List;
import java.util.Map;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.val;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.broadcast.Broadcast;
import org.icgc.dcc.common.core.model.Marking;
import org.icgc.dcc.release.core.function.CombineFields;
import org.icgc.dcc.release.core.function.KeyFields;
import org.icgc.dcc.release.core.job.FileType;
import org.icgc.dcc.release.core.task.GenericTask;
import org.icgc.dcc.release.core.task.TaskContext;
import org.icgc.dcc.release.core.util.Observations;
import org.icgc.dcc.release.job.join.function.CreateOccurrence;
import org.icgc.dcc.release.job.join.function.KeyAnalysisIdAnalyzedSampleIdField;
import org.icgc.dcc.release.job.join.function.KeyDonorMutataionId;
import org.icgc.dcc.release.job.join.function.PairAnalysisIdSampleId;
import org.icgc.dcc.release.job.join.model.DonorSample;

import scala.Tuple2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Optional;

@RequiredArgsConstructor
public class ObservationJoinTask extends GenericTask {

  @NonNull
  private final Broadcast<Map<String, Map<String, DonorSample>>> donorSamplesBroadcast;
  @NonNull
  private final Broadcast<Map<String, Map<String, String>>> sampleSurrogateSampleIdsBroadcast;
  @NonNull
  private final List<String> controlledFields;

  @Override
  public void execute(TaskContext taskContext) {
    val donorSamples = resolveDonorSamples(taskContext, donorSamplesBroadcast);
    val sampleToSurrogageSampleId = getSampleSurrogateSampleIds(taskContext, sampleSurrogateSampleIdsBroadcast);

    val ssmM = parseSsmM(taskContext);
    ssmM.cache();
    val ssmP = parseSsmP(taskContext);
    ssmP.cache();
    val ssmS = parseSsmS(taskContext);
    ssmS.cache();

    // Create SSM
    val ssm = joinSsm(ssmM, ssmP, ssmS, donorSamples, sampleToSurrogageSampleId);
    writeSsm(taskContext, ssm);

    // Create Observations
    val ssmPOpen = filterControlledData(ssmP, controlledFields);
    val observations = joinSsm(ssmM, ssmPOpen, ssmS, donorSamples, sampleToSurrogageSampleId);
    writeObservation(taskContext, observations);

    ssmM.unpersist();
    ssmP.unpersist();
    ssmS.unpersist();
  }

  private JavaRDD<ObjectNode> filterControlledData(JavaRDD<ObjectNode> ssmP, List<String> controlledFields) {
    return ssmP
        .filter(filterControlledRecords())
        .map(removeControlledFields(controlledFields));
  }

  private static Function<ObjectNode, ObjectNode> removeControlledFields(List<String> controlledFields) {
    return row -> {
      row.remove(controlledFields);

      return row;
    };
  }

  private static Function<ObjectNode, Boolean> filterControlledRecords() {
    return row -> {
      Optional<Marking> marking = Observations.getMarking(row);
      checkState(marking.isPresent(), "Failed to resolve marking from {}", row);

      return !marking.get().isControlled();
    };
  }

  private void writeSsm(TaskContext taskContext, JavaRDD<ObjectNode> output) {
    val outputFileType = FileType.SSM;
    writeOutput(taskContext, output, outputFileType);
  }

  private void writeObservation(TaskContext taskContext, JavaRDD<ObjectNode> output) {
    val outputFileType = FileType.OBSERVATION;
    writeOutput(taskContext, output, outputFileType);
  }

  private JavaRDD<ObjectNode> parseSsmM(TaskContext taskContext) {
    return readInput(taskContext, FileType.SSM_M);
  }

  private JavaRDD<ObjectNode> parseSsmP(TaskContext taskContext) {
    return readInput(taskContext, FileType.SSM_P_MASKED_SURROGATE_KEY);
  }

  private JavaRDD<ObjectNode> parseSsmS(TaskContext taskContext) {
    return readInput(taskContext, FileType.SSM_S);
  }

  private static JavaRDD<ObjectNode> joinSsm(JavaRDD<ObjectNode> ssmM, JavaRDD<ObjectNode> ssmP,
      JavaRDD<ObjectNode> ssmS, Map<String, DonorSample> donorSamples, Map<String, String> sampleSurrogageSampleIds) {
    val ssmPrimarySecondary = joinSsmPrimarySecondary(ssmP, ssmS);
    val observations = ssmPrimarySecondary
        .mapToPair(new PairAnalysisIdSampleId())
        .join(ssmM
            .mapToPair(new KeyAnalysisIdAnalyzedSampleIdField()));

    val donorMutationObservations = observations.groupBy(new KeyDonorMutataionId(donorSamples));

    return donorMutationObservations.map(new CreateOccurrence(donorSamples, sampleSurrogageSampleIds));
  }

  private static JavaPairRDD<String, Tuple2<ObjectNode, Optional<Iterable<ObjectNode>>>> joinSsmPrimarySecondary(
      JavaRDD<ObjectNode> ssmP, JavaRDD<ObjectNode> ssmS) {
    return ssmP
        .mapToPair(new KeyFields(NORMALIZER_OBSERVATION_ID))
        .leftOuterJoin(
            ssmS.groupBy(new CombineFields(NORMALIZER_OBSERVATION_ID)));
  }

}