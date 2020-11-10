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
package io.smartdatalake

import configs.{ConfigError, ConfigReader, Result}
import io.smartdatalake.config.SdlConfigObject.{ActionObjectId, ConnectionId, DataObjectId}
import io.smartdatalake.definitions.{Condition, ExecutionMode}
import io.smartdatalake.util.hdfs.SparkRepartitionDef
import io.smartdatalake.util.webservice.KeycloakConfig
import io.smartdatalake.workflow.action.customlogic._
import io.smartdatalake.workflow.dataobject.WebserviceFileDataObject
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.StructType

import scala.language.implicitConversions

package object config {

  /**
   * A [[ConfigReader]] reader that reads [[Either]] values.
   *
   * @param aReader the reader for the [[Left]] value.
   * @param bReader the reader for the [[Right]] value.
   * @tparam A the [[Left]] value type.
   * @tparam B the [[Right]] value type.
   * @return A [[ConfigReader]] containing a [[Left]] or, if it can not be parsed, a [[Right]] value of the corresponding type.
   */
  implicit def eitherReader[A, B](implicit aReader: ConfigReader[A], bReader: ConfigReader[B]): ConfigReader[Either[A, B]] = {
    ConfigReader.fromTry { (c, p) =>
      aReader.read(c, p).map(Left(_)).orElse(bReader.read(c, p).map(Right(_))).valueOrThrow(_.configException)
    }
  }

  /**
   * A [[ConfigReader]] reader that reads [[StructType]] values.
   *
   * This reader parses a [[StructType]] from a DDL string.
   */
  implicit val structTypeReader: ConfigReader[StructType] = ConfigReader.fromTry { (c, p) =>
    StructType.fromDDL(c.getString(p))
  }

  // --------------------------------------------------------------------------------
  // Config readers to circumvent problems related to a bug:
  // The problem is that kxbmap sometimes can not find the correct config reader for
  // some non-trivial types, e.g. List[CustomCaseClass] or Option[CustomCaseClass]
  // see: https://github.com/kxbmap/configs/issues/44
  // TODO: check periodically if still needed, should not be needed with scala 2.13+
  // --------------------------------------------------------------------------------

  implicit val customDfCreatorConfigReader: ConfigReader[CustomDfCreatorConfig] = ConfigReader.derive[CustomDfCreatorConfig]

  implicit val customDfTransformerConfigReader: ConfigReader[CustomDfTransformerConfig] = ConfigReader.derive[CustomDfTransformerConfig]

  implicit def mapDataObjectIdStringReader(implicit mapReader: ConfigReader[Map[String,String]]): ConfigReader[Map[DataObjectId, String]] = {
    ConfigReader.fromConfig { c => mapReader.extract(c).map(_.map{ case (k,v) => (DataObjectId(k), v)})}
  }
  implicit val customDfsTransformerConfigReader: ConfigReader[CustomDfsTransformerConfig] = ConfigReader.derive[CustomDfsTransformerConfig]

  implicit val customFileTransformerConfigReader: ConfigReader[CustomFileTransformerConfig] = ConfigReader.derive[CustomFileTransformerConfig]

  implicit val sparkRepartitionDefReader: ConfigReader[SparkRepartitionDef] = ConfigReader.derive[SparkRepartitionDef]

  implicit val outputModeReader: ConfigReader[OutputMode] = {
    ConfigReader.fromConfig(_.toString.toLowerCase match {
      case "append" => Result.successful(OutputMode.Append())
      case "complete" => Result.successful(OutputMode.Complete())
      case "update" => Result.successful(OutputMode.Update())
      case x => Result.failure(ConfigError(s"$x is not a value of OutputMode. Supported values are append, complete, update."))
    })
  }

  implicit val executionModeReader: ConfigReader[ExecutionMode] = ConfigReader.derive[ExecutionMode]

  implicit val conditionReader: ConfigReader[Condition] = ConfigReader.derive[Condition]

  // --------------------------------------------------------------------------------

  /**
   * A [[ConfigReader]] reader that reads [[KeycloakConfig]] values.
   */
  implicit val keyCloakConfigReader: ConfigReader[Option[KeycloakConfig]] = ConfigReader.fromConfigTry { c =>
    WebserviceFileDataObject.getKeyCloakConfig(c)
  }

  /**
   * A [[ConfigReader]] reader that reads [[DataObjectId]] values.
   */
  implicit val connectionIdReader: ConfigReader[ConnectionId] = ConfigReader.fromTry { (c, p) =>
    ConnectionId(c.getString(p))
  }

  /**
   * A [[ConfigReader]] reader that reads [[DataObjectId]] values.
   */
  implicit val dataObjectIdReader: ConfigReader[DataObjectId] = ConfigReader.fromTry { (c, p) =>
    DataObjectId(c.getString(p))
  }

  /**
   * A [[ConfigReader]] reader that reads [[ActionObjectId]] values.
   */
  implicit val actionObjectIdReader: ConfigReader[ActionObjectId] = ConfigReader.fromTry { (c, p) =>
    ActionObjectId(c.getString(p))
  }
}
