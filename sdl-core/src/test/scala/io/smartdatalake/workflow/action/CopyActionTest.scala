/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2020 ELCA Informatique SA (<https://www.elca.ch>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.smartdatalake.workflow.action

import java.nio.file.Files
import java.time.LocalDateTime

import io.smartdatalake.app.SmartDataLakeBuilderConfig
import io.smartdatalake.config.InstanceRegistry
import io.smartdatalake.definitions.PartitionDiffMode
import io.smartdatalake.testutils.TestUtil
import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.util.hive.HiveUtil
import io.smartdatalake.workflow.action.customlogic.{CustomDfTransformer, CustomDfTransformerConfig}
import io.smartdatalake.workflow.dataobject.{HiveTableDataObject, Table}
import io.smartdatalake.workflow.{ActionPipelineContext, ExecutionPhase, InitSubFeed, SparkSubFeed}
import org.apache.spark.sql.functions.{lit, substring}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.scalatest.{BeforeAndAfter, FunSuite}

class CopyActionTest extends FunSuite with BeforeAndAfter {

  protected implicit val session: SparkSession = TestUtil.sessionHiveCatalog
  import session.implicits._

  private val tempDir = Files.createTempDirectory("test")
  private val tempPath = tempDir.toAbsolutePath.toString

  implicit val instanceRegistry: InstanceRegistry = new InstanceRegistry
  implicit val contextInit: ActionPipelineContext = TestUtil.getDefaultActionPipelineContext
  val contextExec: ActionPipelineContext = contextInit.copy(phase = ExecutionPhase.Exec)

  before {
    instanceRegistry.clear()
  }

  test("copy load with custom transformation class") {

    // setup DataObjects
    val feed = "copy"
    val srcTable = Table(Some("default"), "copy_input")
    val srcDO = HiveTableDataObject( "src1", Some(tempPath+s"/${srcTable.fullName}"), table = srcTable, numInitialHdfsPartitions = 1)
    srcDO.dropTable
    instanceRegistry.register(srcDO)
    val tgtTable = Table(Some("default"), "copy_output", None, Some(Seq("lastname","firstname")))
    val tgtDO = HiveTableDataObject( "tgt1", Some(tempPath+s"/${tgtTable.fullName}"), Seq("lastname"), analyzeTableAfterWrite=true, table = tgtTable, numInitialHdfsPartitions = 1)
    tgtDO.dropTable
    instanceRegistry.register(tgtDO)

    // prepare & start load
    val customTransformerConfig = CustomDfTransformerConfig(className = Some("io.smartdatalake.workflow.action.TestDfTransformer"))
    val action1 = CopyAction("ca", srcDO.id, tgtDO.id, transformer = Some(customTransformerConfig))
    val l1 = Seq(("jonson","rob",5),("doe","bob",3)).toDF("lastname", "firstname", "rating")
    srcDO.writeDataFrame(l1, Seq())
    val srcSubFeed = SparkSubFeed(None, "src1", Seq(PartitionValues(Map("lastname" -> "doe")),PartitionValues(Map("lastname" -> "jonson"))))
    val tgtSubFeed = action1.exec(Seq(srcSubFeed))(session,contextExec).head
    assert(tgtSubFeed.dataObjectId == tgtDO.id)

    val r1 = session.table(s"${tgtTable.fullName}")
      .select($"rating")
      .as[Int].collect().toSeq
    assert(r1.size == 2)
    assert(r1.head == 4) // should be increased by 1 through TestDfTransformer
  }

  test("copy load with custom transformation from code string") {

    // define custom transformation
    val codeStr = """
      import org.apache.spark.sql.{DataFrame, SparkSession}
      def transform(session: SparkSession, options: Map[String,String], df: DataFrame, dataObjectId: String) : DataFrame = {
        import session.implicits._
        df.withColumn("rating", $"rating" + 1)
      }
      // return as function
      transform _
    """
    val customTransformerConfig = CustomDfTransformerConfig(scalaCode = Some(codeStr))

    // setup DataObjects
    val feed = "copy"
    val srcTable = Table(Some("default"), "copy_input")
    val srcDO = HiveTableDataObject( "src1", Some(tempPath+s"/${srcTable.fullName}"), analyzeTableAfterWrite=true, table = srcTable, numInitialHdfsPartitions = 1)
    srcDO.dropTable
    instanceRegistry.register(srcDO)
    val tgtTable = Table(Some("default"), "copy_output", None, Some(Seq("lastname","firstname")))
    val tgtDO = HiveTableDataObject( "tgt1", Some(tempPath+s"/${tgtTable.fullName}"), analyzeTableAfterWrite=true, table = tgtTable, numInitialHdfsPartitions = 1)
    tgtDO.dropTable
    instanceRegistry.register(tgtDO)

    // prepare & start load
    val action1 = CopyAction("ca", srcDO.id, tgtDO.id, transformer = Some(customTransformerConfig))
    val l1 = Seq(("doe","john",5)).toDF("lastname", "firstname", "rating")
    srcDO.writeDataFrame(l1, Seq())
    val srcSubFeed = SparkSubFeed(None, "src1", Seq())
    action1.exec(Seq(srcSubFeed))(session,contextExec)

    val r1 = session.table(s"${tgtTable.fullName}")
      .select($"rating")
      .as[Int].collect().toSeq
    assert(r1.size == 1)
    assert(r1.head == 6) // should be increased by 1 through TestDfTransformer
  }

  test("copy load with transformation from sql code") {

    // setup DataObjects
    val feed = "copy"
    val srcTable = Table(Some("default"), "copy_input")
    val srcDO = HiveTableDataObject( "src1", Some(tempPath+s"/${srcTable.fullName}"), table = srcTable, numInitialHdfsPartitions = 1)
    srcDO.dropTable
    instanceRegistry.register(srcDO)
    val tgtTable = Table(Some("default"), "copy_output", None, Some(Seq("lastname","firstname")))
    val tgtDO = HiveTableDataObject( "tgt1", Some(tempPath+s"/${tgtTable.fullName}"), Seq("lastname"), analyzeTableAfterWrite=true, table = tgtTable, numInitialHdfsPartitions = 1)
    tgtDO.dropTable
    instanceRegistry.register(tgtDO)

    // prepare & start load
    val customTransformerConfig = CustomDfTransformerConfig(sqlCode = Some("select * from copy_input where rating = 5"))
    val action1 = CopyAction("ca", srcDO.id, tgtDO.id, transformer = Some(customTransformerConfig))
    val l1 = Seq(("jonson","rob",5),("doe","bob",3)).toDF("lastname", "firstname", "rating")
    srcDO.writeDataFrame(l1, Seq())
    val srcSubFeed = SparkSubFeed(None, "src1", Seq())
    val tgtSubFeed = action1.exec(Seq(srcSubFeed))(session,contextExec).head
    assert(tgtSubFeed.dataObjectId == tgtDO.id)

    session.table(s"${tgtTable.fullName}").show
    session.table(s"${tgtTable.fullName}").printSchema

    val r1 = session.table(s"${tgtTable.fullName}")
      .select($"lastname")
      .as[String].collect().toSeq
    assert(r1.size == 1) // only one record has rating 5 (see where condition)
    assert(r1.head == "jonson")
  }

  // Almost the same as copy load but without any transformation
  test("copy load without transformer (similar to old ingest action)") {

    // setup DataObjects
    val feed = "notransform"
    val srcTable = Table(Some("default"), "copy_input")
    val srcDO = HiveTableDataObject( "src1", Some(tempPath+s"/${srcTable.fullName}"), table = srcTable, numInitialHdfsPartitions = 1)
    srcDO.dropTable
    instanceRegistry.register(srcDO)
    val tgtTable = Table(Some("default"), "copy_output", None, Some(Seq("lastname","firstname")))
    val tgtDO = HiveTableDataObject( "tgt1", Some(tempPath+s"/${tgtTable.fullName}"), table = tgtTable, numInitialHdfsPartitions = 1)
    tgtDO.dropTable
    instanceRegistry.register(tgtDO)

    // prepare & start load
    val action1 = CopyAction("a1", srcDO.id, tgtDO.id, transformer = None)
    val l1 = Seq(("doe","john",5)).toDF("lastname", "firstname", "rating")
    srcDO.writeDataFrame(l1, Seq())
    val srcSubFeed = SparkSubFeed(None, "src1", Seq())
    val tgtSubFeed = action1.exec(Seq(srcSubFeed))(session,contextExec).head
    assert(tgtSubFeed.dataObjectId == tgtDO.id)

    val r1 = session.table(s"${tgtTable.fullName}")
      .select($"rating")
      .as[Int].collect().toSeq
    assert(r1.size == 1)
    assert(r1.head == 5) // no transformer, rating should stay the same
  }

  test("copy with partition diff execution mode") {

    // setup DataObjects
    val feed = "partitiondiff"
    val srcTable = Table(Some("default"), "copy_input")
    val srcDO = HiveTableDataObject( "src1", Some(tempPath+s"/${srcTable.fullName}"), table = srcTable, partitions = Seq("type"), numInitialHdfsPartitions = 1)
    srcDO.dropTable
    instanceRegistry.register(srcDO)
    val tgtTable = Table(Some("default"), "copy_output", None, Some(Seq("type","lastname","firstname")))
    val tgtDO = HiveTableDataObject( "tgt1", Some(tempPath+s"/${tgtTable.fullName}"), table = tgtTable, partitions = Seq("type"), numInitialHdfsPartitions = 1)
    instanceRegistry.register(tgtDO)
    tgtDO.dropTable

    // prepare action
    val action = CopyAction("a1", srcDO.id, tgtDO.id, executionMode = Some(PartitionDiffMode()))
    val srcSubFeed = InitSubFeed("src1", Seq()) // InitSubFeed needed to test executionMode!

    // prepare & start first load
    val l1 = Seq(("A","doe","john",5)).toDF("type", "lastname", "firstname", "rating")
    val l1PartitionValues = Seq(PartitionValues(Map("type"->"A")))
    srcDO.writeDataFrame(l1, l1PartitionValues) // prepare testdata
    val initOutputSubFeeds = action.init(Seq(srcSubFeed))
    val tgtSubFeed1 = action.exec(Seq(srcSubFeed))(session,contextExec).head

    // check first load
    assert(initOutputSubFeeds.head.asInstanceOf[SparkSubFeed].dataFrame.get.columns.last == "type", "partition columns must be moved last already in init phase")
    assert(tgtSubFeed1.dataObjectId == tgtDO.id)
    assert(tgtSubFeed1.partitionValues.toSet == l1PartitionValues.toSet)
    assert(tgtDO.getDataFrame().count == 1)
    assert(tgtDO.listPartitions.toSet == l1PartitionValues.toSet)

    // prepare & start 2nd load
    val l2 = Seq(("B","pan","peter",11)).toDF("type", "lastname", "firstname", "rating")
    val l2PartitionValues = Seq(PartitionValues(Map("type"->"B")))
    srcDO.writeDataFrame(l2, l2PartitionValues) // prepare testdata
    assert(srcDO.getDataFrame().count == 2) // note: this needs spark.sql.sources.partitionOverwriteMode=dynamic, otherwise the whole table is overwritten
    val tgtSubFeed2 = action.exec(Seq(srcSubFeed))(session,contextExec).head

    // check 2nd load
    assert(tgtSubFeed2.dataObjectId == tgtDO.id)
    assert(tgtSubFeed2.partitionValues.toSet == l2PartitionValues.toSet)
    assert(tgtDO.getDataFrame().count == 2)
    assert(tgtDO.listPartitions.toSet == l1PartitionValues.toSet ++ l2PartitionValues.toSet)
  }

  test("copy load with filter, additional columns and transformer options") {

    // setup DataObjects
    val feed = "copy"
    val srcTable = Table(Some("default"), "copy_input")
    val srcDO = HiveTableDataObject( "src1", Some(tempPath+s"/${srcTable.fullName}"), table = srcTable, numInitialHdfsPartitions = 1)
    srcDO.dropTable
    instanceRegistry.register(srcDO)
    val tgtTable = Table(Some("default"), "copy_output", None, Some(Seq("lastname","firstname")))
    val tgtDO = HiveTableDataObject( "tgt1", Some(tempPath+s"/${tgtTable.fullName}"), Seq("lastname"), analyzeTableAfterWrite=true, table = tgtTable, numInitialHdfsPartitions = 1)
    tgtDO.dropTable
    instanceRegistry.register(tgtDO)

    // prepare & start load
    val customTransformerConfig = CustomDfTransformerConfig(className = Some("io.smartdatalake.workflow.action.TestOptionsDfTransformer"), options = Map("test" -> "test"), runtimeOptions = Map("appName" -> "application"))
    val action1 = CopyAction("ca", srcDO.id, tgtDO.id, transformer = Some(customTransformerConfig), filterClause = Some("lastname='jonson'"), additionalColumns = Some(Map("run_id" -> "runId")))
    val l1 = Seq(("jonson","rob",5),("doe","bob",3)).toDF("lastname", "firstname", "rating")
    srcDO.writeDataFrame(l1, Seq())
    val srcSubFeed = SparkSubFeed(None, "src1", Seq())
    val tgtSubFeed = action1.exec(Seq(srcSubFeed))(session,contextExec).head
    assert(tgtSubFeed.dataObjectId == tgtDO.id)

    val r1 = tgtDO.getDataFrame()
      .select($"rating", $"test", $"run_id")
      .as[(Int,String,Int)].collect().toSeq
    assert(r1 == Seq((6, "test-appTest", 1)))
  }

  test("date to month aggregation with partition value transformation and PartitionDiffMode") {

    // setup DataObjects
    val feed = "copy"
    val srcTable = Table(Some("default"), "copy_input")
    val srcDO = HiveTableDataObject( "src1", Some(tempPath+s"/${srcTable.fullName}"), table = srcTable, partitions = Seq("dt"), numInitialHdfsPartitions = 1)
    srcDO.dropTable
    instanceRegistry.register(srcDO)
    val tgtTable = Table(Some("default"), "copy_output", None, Some(Seq("lastname","firstname")))
    val tgtDO = HiveTableDataObject( "tgt1", Some(tempPath+s"/${tgtTable.fullName}"), Seq("mt"), analyzeTableAfterWrite=true, table = tgtTable, numInitialHdfsPartitions = 1)
    tgtDO.dropTable
    instanceRegistry.register(tgtDO)

    // prepare, simulate
    val contextExec = contextInit.copy(phase = ExecutionPhase.Exec)
    val customTransformerConfig = CustomDfTransformerConfig(className = Some(classOf[TestAggDfTransformer].getName))
    val action1 = CopyAction("ca", srcDO.id, tgtDO.id, transformer = Some(customTransformerConfig), executionMode = Some(PartitionDiffMode(applyPartitionValuesTransform=true)))
    val l1 = Seq(("20100101","jonson","rob",5),("20100103","doe","bob",3)).toDF("dt", "lastname", "firstname", "rating")
    srcDO.writeDataFrame(l1, Seq())
    val srcSubFeed = SparkSubFeed(None, "src1", Seq())
    val tgtSubFeed = action1.init(Seq(srcSubFeed)).head.asInstanceOf[SparkSubFeed]

    // check simulate
    assert(tgtSubFeed.dataObjectId == tgtDO.id)
    val expectedPartitionValues = Seq(PartitionValues(Map("mt" -> "201001")))
    assert(tgtSubFeed.partitionValues == expectedPartitionValues)
    assert(tgtSubFeed.dataFrame.get.columns.contains("mt"))

    // run
    action1.exec(Seq(srcSubFeed))(session,contextExec)
    assert(tgtDO.getDataFrame().count == 2)

    // simulate next run
    action1.reset
    intercept[NoDataToProcessWarning](action1.init(Seq(srcSubFeed)).head.asInstanceOf[SparkSubFeed])
  }

}

class TestDfTransformer extends CustomDfTransformer {
  def transform(session: SparkSession, options: Map[String,String], df: DataFrame, dataObjectId: String) : DataFrame = {
    import session.implicits._
    import io.smartdatalake.util.misc.DataFrameUtil.DfSDL
    df.withColumn("rating", $"rating" + 1)
  }
}

class TestOptionsDfTransformer extends CustomDfTransformer {
  def transform(session: SparkSession, options: Map[String,String], df: DataFrame, dataObjectId: String) : DataFrame = {
    import session.implicits._
    df.withColumn("rating", $"rating" + 1)
      .withColumn("test", lit(options("test")+"-"+options("appName")))
  }
}

class TestAggDfTransformer extends CustomDfTransformer {
  def transform(session: SparkSession, options: Map[String,String], df: DataFrame, dataObjectId: String) : DataFrame = {
    import session.implicits._
    df.withColumn("mt", substring($"dt",1,6))
  }
  override def transformPartitionValues(options: Map[String, String], partitionValues: Seq[PartitionValues]): Map[PartitionValues,PartitionValues] = {
    partitionValues.map(pv => (pv,PartitionValues(Map("mt" -> pv("dt").toString.take(6))))).toMap
  }
}
