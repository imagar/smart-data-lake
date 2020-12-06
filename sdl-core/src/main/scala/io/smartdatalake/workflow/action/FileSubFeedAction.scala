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

import io.smartdatalake.definitions.ExecutionMode
import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.util.misc.PerformanceUtils
import io.smartdatalake.workflow.dataobject._
import io.smartdatalake.workflow._
import org.apache.spark.sql.{SaveMode, SparkSession}

abstract class FileSubFeedAction extends Action {

  /**
   * Input [[FileRefDataObject]] which can CanCreateInputStream
   */
  def input: FileRefDataObject with CanCreateInputStream

  /**
   * Output [[FileRefDataObject]] which can CanCreateOutputStream
   */
  def output:  FileRefDataObject with CanCreateOutputStream

  /**
   * Recursive Inputs on FileSubFeeds are not supported so empty Seq is set.
   *  @return
   */
  override def recursiveInputs: Seq[FileRefDataObject with CanCreateInputStream] = Seq()

  /**
   * Initialize Action with a given [[FileSubFeed]]
   * Note that this only checks the prerequisits to do the processing and simulates the output FileRef's that would be created.
   *
   * @param subFeed subFeed to be processed (referencing files to be read)
   * @return processed subFeed (referencing files that would be written by this action)
   */
  def initSubFeed(subFeed: FileSubFeed)(implicit session: SparkSession, context: ActionPipelineContext): FileSubFeed

  /**
   * Executes Action for a given [[FileSubFeed]]
   *
   * @param subFeed subFeed to be processed (referencing files to be read)
   * @return processed subFeed (referencing files written by this action)
   */
  def execSubFeed(subFeed: FileSubFeed)(implicit session: SparkSession, context: ActionPipelineContext): FileSubFeed

  override def prepare(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    super.prepare
    executionMode.foreach(_.prepare(id))
  }

  private def prepareSubFeed(subFeed: SubFeed)(implicit session: SparkSession, context: ActionPipelineContext): FileSubFeed = {
    // convert subfeeds to FileSubFeed type or initialize if not yet existing
    var preparedSubFeed = FileSubFeed.fromSubFeed(subFeed)
    // apply execution mode
    preparedSubFeed = executionMode match {
      case Some(mode) =>
        mode.apply(id, input, output, preparedSubFeed) match {
          case Some((partitionValues, _)) => preparedSubFeed.copy(partitionValues = partitionValues)
          case None => preparedSubFeed
        }
      case _ => preparedSubFeed
    }
    // validate partition values existing for input
    if (subFeed.partitionValues.nonEmpty && (context.phase==ExecutionPhase.Exec || subFeed.isDAGStart)) {
      val expectedPartitions = input.filterExpectedPartitionValues(subFeed.partitionValues)
      val missingPartitionValues = PartitionValues.checkExpectedPartitionValues(input.listPartitions, expectedPartitions)
      assert(missingPartitionValues.isEmpty, s"($id) partitions $missingPartitionValues missing for ${input.id}")
    }
    // break lineage if requested
    if (breakFileRefLineage) preparedSubFeed.breakLineage else preparedSubFeed
  }

  /**
   * Updates the partition values of a SubFeed to the partition columns of an output, removing not existing columns from the partition values.
   *
   * @param output output DataObject
   * @param subFeed transformed SubFeed
   * @return SubFeed with updated partition values.
   */
  private def validateAndUpdateSubFeed(output: DataObject, subFeed: FileSubFeed )(implicit session: SparkSession): FileSubFeed = {
    val updatedSubFeed = output match {
      case partitionedDO: CanHandlePartitions =>
        // remove superfluous partitionValues
        subFeed.updatePartitionValues(partitionedDO.partitions)
      case _ => subFeed.clearPartitionValues()
    }
    updatedSubFeed.clearDAGStart().copy(dataObjectId = output.id)
  }

  /**
   * Action.init implementation
   */
  override final def init(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SubFeed] = {
    assert(subFeeds.size == 1, s"Only one subfeed allowed for FileSubFeedAction (Action $id, inputSubfeed's ${subFeeds.map(_.dataObjectId).mkString(",")}")
    val preparedSubFeed = prepareSubFeed(subFeeds.head)
    // transform (file transformation is limited to initialization in init phase)
    val transformedSubFeed = initSubFeed(preparedSubFeed)
    // update subFeed
    Seq(validateAndUpdateSubFeed(output,transformedSubFeed))
  }

  /**
   * Action.exec implementation
   */
  override final def exec(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SubFeed] = {
    assert(subFeeds.size == 1, s"Only one subfeed allowed for FileSubFeedActions (Action $id, inputSubfeed's ${subFeeds.map(_.dataObjectId).mkString(",")})")
    val preparedSubFeed = prepareSubFeed(subFeeds.head)
    // delete existing files on overwrite
    if (output.saveMode == SaveMode.Overwrite) {
      if (output.partitions.nonEmpty)
        if (preparedSubFeed.partitionValues.nonEmpty) output.deletePartitions(preparedSubFeed.partitionValues)
        else logger.warn(s"($id) Cannot delete data from partitioned data object ${output.id} as no partition values are given but saveMode=overwrite")
      else output.deleteAll
    }
    // transform
    logger.info( s"($id) start writing files to ${output.id}" + (if (preparedSubFeed.partitionValues.nonEmpty) s", partitionValues ${preparedSubFeed.partitionValues.mkString(" ")}" else ""))
    val (transformedSubFeed,d) = PerformanceUtils.measureDuration {
      execSubFeed(preparedSubFeed)
    }
    val filesWritten = transformedSubFeed.fileRefs.get.size.toLong
    logger.info(s"($id) finished writing files to ${output.id}: duration=$d files_written=$filesWritten")
    // send metric to action (for file subfeeds this has to be done manually while spark subfeeds get's the metrics via a spark events listener)
    onRuntimeMetrics(Some(output.id), GenericMetrics(s"$id-${output.id}", 1, Map("duration"->d, "files_written"->filesWritten)))
    // update subFeed
    Seq(validateAndUpdateSubFeed(output,transformedSubFeed))
  }

  override final def postExec(inputSubFeeds: Seq[SubFeed], outputSubFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    super.postExec(inputSubFeeds,outputSubFeeds)
    assert(inputSubFeeds.size == 1, s"Only one inputSubFeed allowed for FileSubFeedAction (Action $id, inputSubfeed's ${inputSubFeeds.map(_.dataObjectId).mkString(",")})")
    assert(outputSubFeeds.size == 1, s"Only one outputSubFeed allowed for FileSubFeedAction (Action $id, inputSubfeed's ${outputSubFeeds.map(_.dataObjectId).mkString(",")})")
    postExecSubFeed(inputSubFeeds.head, outputSubFeeds.head)
  }

  def postExecSubFeed(inputSubFeed: SubFeed, outputSubFeed: SubFeed)(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    // delete Input Files if desired
    if (deleteDataAfterRead()) (input, outputSubFeed) match {
      case (fileRefInput: FileRefDataObject, fileSubFeed: FileSubFeed) =>
        fileSubFeed.processedInputFileRefs.foreach(fileRefs => fileRefInput.deleteFileRefs(fileRefs))
      case x => throw new IllegalStateException(s"Unmatched case $x")
    }
  }

  /**
   * Stop propagating input FileRefs through action and instead get new FileRefs from DataObject according to the SubFeed's partitionValue.
   * This is needed to reprocess all files of a path/partition instead of the FileRef's passed from the previous Action.
   */
  def breakFileRefLineage: Boolean

  /**
   * Execution mode if this Action is a start node of a DAG run
   */
  def executionMode: Option[ExecutionMode]

  /**
   * If true delete files after they are successfully processed.
   */
  def deleteDataAfterRead(): Boolean

}
