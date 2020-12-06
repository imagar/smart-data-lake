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
package io.smartdatalake.workflow.dataobject

import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.workflow.ActionPipelineContext
import org.apache.spark.sql.types.{DataType, StructType}
import org.apache.spark.sql.{DataFrame, SparkSession}

private[smartdatalake] trait CanCreateDataFrame {

  def getDataFrame(partitionValues: Seq[PartitionValues] = Seq())(implicit session: SparkSession, context: ActionPipelineContext) : DataFrame

  /**
   * Creates the read schema based on a given write schema.
   * Normally this is the same, but some DataObjects can remove & add columns on read (e.g. KafkaTopicDataObject, SparkFileDataObject)
   * In this cases we have to break the DataFrame lineage und create a dummy DataFrame in init phase.
   */
  def createReadSchema(writeSchema: StructType)(implicit session: SparkSession): StructType = writeSchema

  protected def addFieldIfNotExisting(writeSchema: StructType, colName: String, dataType: DataType): StructType = {
    if (!writeSchema.fieldNames.contains(colName)) writeSchema.add(colName, dataType)
    else writeSchema
  }
}
