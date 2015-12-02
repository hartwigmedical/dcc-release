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
package org.icgc.dcc.release.core.task;

import static org.icgc.dcc.common.core.util.FormatUtils.formatBytes;
import static org.icgc.dcc.release.core.util.JavaRDDs.emptyRDD;
import static org.icgc.dcc.release.core.util.JavaRDDs.exists;
import static org.icgc.dcc.release.core.util.JavaRDDs.logPartitions;

import java.util.List;
import java.util.regex.Pattern;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.spark.api.java.JavaRDD;
import org.icgc.dcc.common.hadoop.fs.HadoopUtils;
import org.icgc.dcc.release.core.job.FileType;
import org.icgc.dcc.release.core.util.JavaRDDs;
import org.icgc.dcc.release.core.util.ObjectNodeRDDs;
import org.icgc.dcc.release.core.util.Partitions;

import com.fasterxml.jackson.databind.node.ObjectNode;

@Slf4j
public abstract class GenericTask implements Task {

  private final String name;

  public GenericTask(String name) {
    this.name = Task.getName(this.getClass(), name);
  }

  public GenericTask() {
    this.name = Task.getName(this.getClass());
  }

  @Override
  public String getName() {
    return name;
  }

  protected JobConf createJobConf(TaskContext taskContext) {
    val sparkContext = taskContext.getSparkContext();

    return new JobConf(sparkContext.hadoopConfiguration());
  }

  protected JavaRDD<ObjectNode> readInput(TaskContext taskContext, FileType inputFileType) {
    val conf = createJobConf(taskContext);

    return readInput(taskContext, conf, inputFileType);
  }

  /**
   * @param size split/combine size in MBytes
   */
  protected JavaRDD<ObjectNode> readInput(TaskContext taskContext, JobConf hadoopConf, FileType inputFileType, long size) {
    val maxFileSize = size * 1024L * 1024L;

    log.info("Setting input split size of {}", formatBytes(maxFileSize));
    val splitSize = Long.toString(maxFileSize);
    hadoopConf.set("mapred.min.split.size", splitSize);
    hadoopConf.set("mapred.max.split.size", splitSize);

    val sparkContext = taskContext.getSparkContext();
    val path = taskContext.getPath(inputFileType);

    val input = taskContext.isCompressOutput() ?
        ObjectNodeRDDs.combineObjectNodeSequenceFile(sparkContext, path, hadoopConf) :
        ObjectNodeRDDs.combineObjectNodeFile(sparkContext, path, hadoopConf);

    JavaRDDs.logPartitions(log, input.partitions());

    return input;
  }

  protected JavaRDD<ObjectNode> readInput(TaskContext taskContext, JobConf conf, FileType inputFileType) {
    if (isReadAll(taskContext, inputFileType)) {
      return readAllInput(taskContext, conf, inputFileType);
    }

    val sparkContext = taskContext.getSparkContext();
    val filePath = taskContext.getPath(inputFileType);

    if (!exists(sparkContext, filePath)) {
      log.warn("{} does not exist. Skipping...", filePath);

      return emptyRDD(sparkContext);
    }

    val input = readInput(taskContext, taskContext.getPath(inputFileType), conf);
    logPartitions(log, input.partitions());

    return input;
  }

  private static JavaRDD<ObjectNode> readAllInput(TaskContext taskContext, JobConf conf, FileType inputFileType) {
    val fileTypePath = new Path(taskContext.getJobContext().getWorkingDir(), inputFileType.getDirName());
    val inputPaths = resolveInputPaths(taskContext, fileTypePath);
    val sparkContext = taskContext.getSparkContext();
    JavaRDD<ObjectNode> result = emptyRDD(sparkContext);

    for (val inputPath : inputPaths) {
      val input = readInput(taskContext, inputPath.toString(), conf);
      result = result.union(input);
    }

    return result;
  }

  private static List<Path> resolveInputPaths(TaskContext taskContext, Path fileTypePath) {
    return HadoopUtils.lsDir(taskContext.getFileSystem(), fileTypePath,
        Pattern.compile(Partitions.PARTITION_NAME + ".*"));
  }

  protected void writeOutput(TaskContext taskContext, JavaRDD<ObjectNode> processed, FileType outputFileType) {
    val outputPath = taskContext.getPath(outputFileType);

    writeOutput(processed, outputPath, taskContext.isCompressOutput());
  }

  protected void writeOutput(JavaRDD<ObjectNode> processed, String outputPath, boolean compressOutput) {
    if (compressOutput) {
      ObjectNodeRDDs.saveAsSequenceObjectNodeFile(processed, outputPath);
    } else {
      ObjectNodeRDDs.saveAsTextObjectNodeFile(processed, outputPath);
    }
  }

  private static JavaRDD<ObjectNode> readInput(TaskContext taskContext, String path, JobConf conf) {
    val sparkContext = taskContext.getSparkContext();
    if (taskContext.isCompressOutput()) {
      return ObjectNodeRDDs.sequenceObjectNodeFile(sparkContext, path, conf);
    } else {
      return ObjectNodeRDDs.textObjectNodeFile(sparkContext, path, conf);
    }
  }

  private static boolean isReadAll(TaskContext taskContext, FileType inputFileType) {
    return inputFileType.isPartitioned() && !taskContext.getProjectName().isPresent();
  }

}