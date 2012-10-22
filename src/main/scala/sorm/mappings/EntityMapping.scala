package sorm.mappings

import sext._
import sorm._
import connection.Connection
import core._
import jdbc.ResultSetView
import persisted.Persisted
import reflection.Reflection

class EntityMapping 
  ( val reflection : Reflection, 
    val membership : Option[Membership], 
    val settings : Map[Reflection, EntitySettings],
    val connection : Connection )
  extends MasterTableMapping {

  lazy val properties
    = reflection.properties.map{case (n, r) => n -> Mapping(r, Membership.EntityProperty(n, this), settings, connection)}
  lazy val mappings // todo: add id
    = properties.values.toStream
  lazy val primaryKeyColumns
    = id.column +: Stream()
  lazy val id
    = new ValueMapping(Reflection[Long], Some(Membership.EntityId(this)), settings, connection)
  lazy val generatedColumns = id.column +: Stream()

  def parseResultSet(rs: ResultSetView)
    = rs.byNameRowsTraversable.toStream
        .headOption
        .map( row => Persisted(
          properties.mapValues( _.valueFromContainerRow(row) ),
          row("id").asInstanceOf[Long],
          reflection
        ) )
        .get

  def delete ( value : Any ) {
    value match {
      case value : Persisted =>
        ("id" -> value.id) $ (Stream(_)) $ (tableName -> _) $$ connection.delete
      case _ =>
        throw new SormException("Attempt to delete an unpersisted entity: " + value)
    }
  }

  def valuesForContainerTableRow ( value : Any )
    = value match {
        case value : Persisted =>
          ( memberName + "$id" -> value.id ) +: Stream()
        case _ =>
          throw new SormException("Attempt to refer to an unpersisted entity: " + value)
      }

  def save ( value : Any ) : Persisted
    = {
      val propertyValues = properties.map{ case (n, m) => (n, m, reflection.propertyValue(n, value.asInstanceOf[AnyRef])) }.toStream
      val rowValues = propertyValues.flatMap{ case (n, m, v) => m.valuesForContainerTableRow(v) }

      value match {
        case value : Persisted =>
          val pk = Stream(value.id)
          connection.update(tableName, rowValues, pk $ (primaryKeyColumnNames zip _))
          propertyValues.foreach{ case (n, m, v) => m.update(v, pk) }
          value
        case _ =>
          val id = connection.insertAndGetGeneratedKeys(tableName, rowValues).ensuring(_.length == 1).head.asInstanceOf[Long]
          propertyValues.foreach{ case (n, m, v) => m.insert(v, Stream(id)) }
          Persisted( propertyValues.map(t => t._1 -> t._3).toMap, id, reflection )
      }
    }

  override lazy val uniqueKeysColumnNames
    = settings get reflection map (_.uniqueKeys) getOrElse Set() map (_ map properties flatMap (_.columnsForContainer.map(_.name))) filter (_.nonEmpty)

  override lazy val indexesColumnNames
    = settings get reflection map (_.indexes) getOrElse Set() map (_ map properties flatMap (_.columnsForContainer.map(_.name))) filter (_.nonEmpty)

  lazy val uniqueKeys
    = settings get reflection map (_.uniqueKeys) getOrElse Set()

  lazy val indexes
    = settings get reflection map (_.indexes) getOrElse Set()
}