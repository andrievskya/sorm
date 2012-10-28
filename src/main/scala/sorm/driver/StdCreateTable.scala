package sorm.driver

import sext._, embrace._
import sorm._
import ddl._
import jdbc._

trait StdCreateTable {
  protected def quote ( x : String ) : String
  protected def connection : JdbcConnection
  def createTable ( table : Table ) {
    table $ statement $ connection.executeUpdate
  }
  private def statement ( table : Table ) : Statement
    = table $ tableDdl $ (Statement(_, Seq()))
  protected def tableDdl ( t : Table ) : String 
    = {
      val Table(name, columns, primaryKey, uniqueKeys, indexes, foreingKeys) = t
      val statements = 
        columns.map(columnDdl) ++:
        primaryKey.$(primaryKeyDdl) +: 
        indexes.map(indexDdl) ++:
        uniqueKeys.map(uniqueKeyDdl) ++:
        foreingKeys.map(foreingKeyDdl).toStream
      "CREATE TABLE " + quote(name) + 
      ( "\n( " + statements.mkString(",\n").indent(2).trim + " )" ).indent(2)
    }
  protected def primaryKeyDdl ( columns : Seq[String] )
    = "PRIMARY KEY (" + columns.view.map(quote).mkString(", ") + ")"
  protected def indexDdl ( columns : Seq[String] )
    = "INDEX (" + columns.view.map(quote).mkString(", ") + ")"
  protected def uniqueKeyDdl ( columns : Seq[String] )
    = "UNIQUE (" + columns.view.map(quote).mkString(", ") + ")"
  protected def foreingKeyDdl ( fk : ForeignKey )
    = {
      val ForeignKey(table, bindings, onDelete, onUpdate) = fk
      "FOREIGN KEY\n" +
      ( "( " + bindings.view.unzip._1.map(quote).mkString(", ") + " )\n" +
        "REFERENCES " + quote(table) + "\n" +
        "( " + bindings.view.unzip._2.map(quote).mkString(", ") + " )\n" +
        "ON DELETE " + referenceOptionDdl(onDelete) + "\n" +
        "ON UPDATE " + referenceOptionDdl(onUpdate) 
      ) .indent(2)
    }
  protected def referenceOptionDdl ( o : ReferenceOption )
    = {
      import ReferenceOption._
      o match {
        case Restrict   => "RESTRICT"
        case Cascade    => "CASCADE"
        case NoAction   => "NO ACTION"
        case SetNull    => "SET NULL"
        case SetDefault => "SET DEFAULT"
      }
    }
  protected def columnDdl ( c : Column )
    = quote(c.name) + " " + columnTypeDdl(c.t) +
      ( if( c.nullable ) " NULL" else " NOT NULL" ) +
      ( if( c.autoIncrement ) " AUTO_INCREMENT" else "" )
  protected def columnTypeDdl ( t : ColumnType )
    = {
      import ColumnType._
      t match {
        case Enum(values) => "ENUM(" + values.map("'" + _ + "'").mkString(", ") + ")"
        case Time => "TIME"
        case Date => "DATE"
        case TimeStamp => "TIMESTAMP"
        case Integer => "INTEGER"
        case VarChar => "VARCHAR(255)"
        case Double => "DOUBLE"
        case Float => "FLOAT"
        case Text => "MEDIUMTEXT"
        case BigInt => "BIGINT"
        case Boolean => "TINYINT(1)"
        case Decimal => "DECIMAL"
        case SmallInt => "SMALLINT"
        case TinyInt => "TINYINT"
      }
    }
}
