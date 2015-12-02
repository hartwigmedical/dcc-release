package org.icgc.dcc.release.test.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.util.List;
import java.util.Optional;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.val;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.icgc.dcc.common.core.util.stream.Collectors;
import org.icgc.dcc.release.core.job.DefaultJobContext;
import org.icgc.dcc.release.core.job.FileType;
import org.icgc.dcc.release.core.job.JobContext;
import org.icgc.dcc.release.core.job.JobType;
import org.icgc.dcc.release.core.task.DefaultTaskContext;
import org.icgc.dcc.release.core.task.TaskContext;
import org.icgc.dcc.release.core.task.TaskExecutor;
import org.icgc.dcc.release.core.util.Partitions;
import org.icgc.dcc.release.test.model.TestFile;
import org.icgc.dcc.release.test.model.TestFile.TestFileBuilder;
import org.icgc.dcc.release.test.util.TestFiles;
import org.icgc.dcc.release.test.util.TestJsonNodes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Table;
import com.google.common.util.concurrent.MoreExecutors;

public abstract class AbstractJobTest {

  /**
   * Constants.
   */
  protected static final String TEST_FIXTURES_DIR = "src/test/resources/fixtures";
  protected static final String INPUT_TEST_FIXTURES_DIR = TEST_FIXTURES_DIR + "/input";
  protected static final String OUTPUT_TEST_FIXTURES_DIR = TEST_FIXTURES_DIR + "/output";
  protected static final String RELEASE_VERSION = "ICGC19-0-2";

  /**
   * Collaborators.
   */
  protected JavaSparkContext sparkContext;
  protected TaskExecutor taskExecutor;
  protected FileSystem fileSystem;
  protected File workingDir;

  /**
   * State.
   */
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Before
  @SneakyThrows
  public void setUp() {
    val sparkConf = new SparkConf().setAppName("test").setMaster("local");
    sparkConf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer");
    sparkConf.set("spark.kryo.registrator", "org.icgc.dcc.release.core.util.CustomKryoRegistrator");
    sparkConf.set("spark.task.maxFailures", "0");

    this.sparkContext = new JavaSparkContext(sparkConf);

    val executor = MoreExecutors.sameThreadExecutor();
    this.fileSystem = FileSystem.getLocal(new Configuration());
    this.taskExecutor = new TaskExecutor(executor, sparkContext, fileSystem);

    this.workingDir = tmp.newFolder("working");
  }

  @After
  public void shutDown() {
    sparkContext.stop();
    sparkContext = null;
    System.clearProperty("spark.master.port");
  }

  protected void given(TestFileBuilder... inputFileBuilders) {
    for (val inputFileBuilder : inputFileBuilders) {
      createInputFile(inputFileBuilder.build());
    }
  }

  protected void given(File inputDirectory) {
    File[] fileTypes = inputDirectory.listFiles();
    processFileTypes(fileTypes);
  }

  private void processFileTypes(File[] fileTypes) {
    for (File fileTypeDir : fileTypes) {
      if (fileTypeDir.isFile()) {
        continue;
      }
      String fileTypeDirName = fileTypeDir.getName();
      File[] projects = fileTypeDir.listFiles();
      if (areProjects(projects)) {
        processProjects(fileTypeDirName, projects);
      } else {
        processFiles(fileTypeDirName, projects);
      }
    }
  }

  private void processFiles(String fileTypeDirName, File[] projects) {
    for (val project : projects) {
      val testFile = inputFile()
          .fileType(FileType.valueOf(fileTypeDirName.toUpperCase()))
          .path(project.getAbsolutePath())
          .build();
      createInputFile(testFile);
    }
  }

  private boolean areProjects(File[] projects) {
    val projectsList = ImmutableList.copyOf(projects);
    if (projectsList.stream().allMatch(f -> f.getName().startsWith("project_name"))) {
      return true;
    } else if (projectsList.stream().allMatch(f -> isPartFile(f))) {
      return false;
    }

    throw new IllegalArgumentException();
  }

  private void processProjects(String fileTypeDirName, File[] projects) {
    for (File projectDir : projects) {
      if (projectDir.isFile()) {
        continue;
      }
      String projectName = projectDir.getName().split("=")[1];
      File[] files = projectDir.listFiles();
      createInputFiles(files, projectName, fileTypeDirName);
    }
  }

  private void createInputFiles(File[] files, String projectName, String fileTypeDirName) {
    for (File file : files) {
      String fileName = file.getName();
      if (fileName.startsWith("part-")) {
        TestFile testFile =
            TestFile.builder().projectName(projectName).fileType(FileType.valueOf(fileTypeDirName.toUpperCase()))
                .fileName(fileName)
                .path(file.getAbsolutePath()).build();
        createInputFile(testFile);
      }
    }
  }

  protected JobContext createJobContext(JobType type) {
    return createJobContext(type, ImmutableList.of(""));
  }

  @SuppressWarnings("unchecked")
  protected JobContext createJobContext(JobType type, List<String> projectNames) {
    return new DefaultJobContext(type, RELEASE_VERSION, projectNames, "/dev/null",
        workingDir.toString(), mock(Table.class), taskExecutor, false);
  }

  protected TaskContext createTaskContext(JobType jobType) {
    return new DefaultTaskContext(createJobContext(jobType), sparkContext, fileSystem, Optional.empty(), false);
  }

  protected TaskContext createTaskContext(JobType jobType, String projectName) {
    return new DefaultTaskContext(createJobContext(jobType), sparkContext, fileSystem, Optional.of(projectName), false);
  }

  protected void createInputFile(TestFile inputFile) {
    val fileTypeDirectory = getFileTypeDirectory(inputFile.getFileType());
    if (!fileTypeDirectory.exists()) {
      fileTypeDirectory.mkdirs();
    }

    val target = inputFile.isProjectPartitioned() ?
        getProjectFileTypeDirectory(inputFile.getProjectName(), inputFile.getFileType()) :
        getFileTypeFile(inputFile.getFileType());
    if (!isPartFile(target) && !target.exists()) {
      target.mkdirs();
    }

    if (inputFile.isFile()) {
      val sourceFile = new File(inputFile.getPath());
      val targetFile = !inputFile.hasFileName() || isPartFile(target) ?
          target : new File(target, sourceFile.getName());
      TestFiles.writeInputFile(sourceFile, targetFile);
    } else {
      val targetFile = new File(target, "part-00000");
      TestFiles.writeInputFile(inputFile.getRows(), targetFile);
    }
  }

  private static boolean isPartFile(File target) {
    return target.getName().startsWith("part-");
  }

  protected TaskExecutor createTaskExecutor() {
    return new TaskExecutor(MoreExecutors.sameThreadExecutor(), sparkContext, fileSystem);
  }

  private File getFileTypeFile(FileType fileType) {
    return new File(getFileTypeDirectory(fileType), "part-00000");
  }

  private File getFileTypeDirectory(FileType fileType) {
    val type = new File(workingDir, fileType.getDirName());

    return type;
  }

  private File getProjectFileTypeDirectory(String projectName, FileType fileType) {
    return new File(getFileTypeDirectory(fileType), Partitions.getPartitionName(projectName));
  }

  private File getProjectFileTypeFile(String projectName, FileType fileType) {
    return new File(getProjectFileTypeDirectory(projectName, fileType), "part-00000");
  }

  protected List<ObjectNode> producesFile(FileType fileType) {
    return producesFile(null, fileType);
  }

  @SneakyThrows
  protected List<ObjectNode> producesFile(String projectName, FileType fileType) {
    val file = projectName == null ? getFileTypeFile(fileType) : getProjectFileTypeFile(projectName, fileType);

    return TestFiles.readInputFile(file);
  }

  @SneakyThrows
  protected List<ObjectNode> produces(String projectName, FileType fileType) {
    val file = projectName == null ?
        getFileTypeDirectory(fileType) :
        getProjectFileTypeDirectory(projectName, fileType);

    return TestFiles.readInputDirectory(file);
  }

  @SneakyThrows
  protected List<ObjectNode> produces(FileType fileType) {
    return produces(null, fileType);
  }

  protected static ObjectNode row(@NonNull String json) {
    return TestJsonNodes.$(json);
  }

  protected static TestFileBuilder inputFile() {
    return TestFile.builder();
  }

  protected static TestFileBuilder inputFile(String projectName) {
    return inputFile().projectName(projectName);
  }

  protected String resolvePath(String fileName) {
    return new File(TEST_FIXTURES_DIR + "/" + fileName).getAbsolutePath();
  }

  /**
   * Compares actual output with output located in {@link OUTPUT_TEST_FIXTURES_DIR}.
   */
  protected void verifyResult(FileType fileType) {
    val actualResult = produces(fileType);
    val expectedFile = resolveExpectedFile(fileType);
    val expectedResult = TestFiles.readInputFile(expectedFile);
    compareResults(normalizeJson(expectedResult), normalizeJson(actualResult));
  }

  /**
   * Compares actual output with output located in {@link OUTPUT_TEST_FIXTURES_DIR}.
   */
  protected void verifyResult(String projectName, FileType fileType) {
    val actualResult = produces(projectName, fileType);
    val expectedFile = resolveExpectedFile(projectName, fileType);
    val expectedResult = TestFiles.readInputFile(expectedFile);
    compareResults(normalizeJson(expectedResult), normalizeJson(actualResult));
  }

  private static List<JsonNode> normalizeJson(List<? extends JsonNode> expectedResult) {
    return expectedResult.stream()
        .map(j -> TestJsonNodes.sortFields(j))
        .collect(Collectors.toImmutableList());
  }

  private static void compareResults(List<JsonNode> expectedResult, List<JsonNode> actualResult) {
    assertThat(actualResult).hasSameSizeAs(expectedResult);
    assertThat(actualResult).containsExactlyElementsOf(expectedResult);
  }

  private static File resolveExpectedFile(FileType fileType) {
    return resolveExpectedFile(null, fileType);
  }

  private static File resolveExpectedFile(String projectName, FileType fileType) {
    val parentDir = projectName == null ?
        new File(OUTPUT_TEST_FIXTURES_DIR) :
        new File(OUTPUT_TEST_FIXTURES_DIR, Partitions.getPartitionName(projectName));

    return new File(parentDir, fileType.getDirName());
  }

}