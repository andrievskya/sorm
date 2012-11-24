package sorm

import sorm._
import core._
import persisted._
import reflection._
import mappings._
import jdbc._

import sext._, embrace._
import reflect.runtime.universe._
import com.weiglewilczek.slf4s.Logging

/**
 * The instance of SORM
 * @param entities A list of entity settings describing the entities to be
 *                 registered with this instance
 * @param url A url of database to connect to. For instance, to connect to a
 *            database `test` on MySQL server running on your computer it will
 *            be: `jdbc:mysql://localhost/test`
 * @param user A username used for connection
 * @param password A password used for connection
 * @param poolSize A size of connection pool. Determines how many connections
 *                 to the db will be kept at max. Useful for multithreaded
 *                 databases.
 * @param initMode An initialization mode for this instance
 */
class Instance
  ( entities : Traversable[Entity],
    url : String,
    user : String = "",
    password : String = "",
    poolSize : Int = 1,
    initMode : InitMode = InitMode.Create )
  extends Instance.Initialization(entities, url, user, password, poolSize, initMode)
  with Instance.Api

object Instance {

  import org.joda.time.DateTime

  trait Api extends Logging {

    protected val connector : Connector

    protected val mappings : Map[Reflection, EntityMapping]

    private def mapping
      [ T : TypeTag ]
      = {
        def mapping( r : Reflection )
          = mappings.get(r)
              .getOrElse {
                throw new SormException(
                  "Entity `" + r.name + "` is not registered"
                )
              }
        mapping(Reflection[T].mixinBasis)
      }

    /**
     * Return the [[sorm.Querier]] object for performing a read-query on a specified entity type.
     * 
     * @tparam T The entity type
     * @return The accessor object. An abstraction over all kinds of supported SELECT-queries.
     */
    def query [ T <: AnyRef : TypeTag ]
      = Querier[T](mapping, connector)

    /**
     * Select entities using a plain sql statement. This function allows to execute custom queries which in certain situations may be better optimized than the ones generated by SORM.
     *
     * Please note that the statement must select only the `id` column.
     *
     * ==Example:==
     * {{{
     *  Db.fetchWithSql[Artist]("SELECT id FROM artist WHERE name=? || name=?", "Beatles", "The Beatles")
     * }}}
     * @param template The sql with question-symbols used as placeholders for values
     * @param values The values
     * @tparam T The type of entities to fetch
     * @return Matching entities of type `T`
     */
    def fetchWithSql
      [ T <: AnyRef : TypeTag ]
      ( template : String,
        values : Any* )
      : Seq[T with Persisted]
      = connector.withConnection{ cx =>
          jdbc.Statement.simple(template, values)
            .$( cx.query(_)(_.byNameRowsTraversable.toList).toStream )
            .ensuring( 
              _.headOption
                .map( k => k.keys == Set("id") )
                .getOrElse(true),
              "The sql-statement must select only the `id`-column"
            )
            .map(
              mapping[T].fetchByPrimaryKey(_, cx).asInstanceOf[T with Persisted]
            )
            .toList
        }

    /**
     * Fetch an existing entity by id. Will throw an exception if the entity doesn't exist. 
     * @param id The id
     * @return An entity instance with a [[sorm.Persisted]] trait mixed in
     */
    def fetchById
      [ T <: AnyRef : TypeTag ]
      ( id : Long )
      : T with Persisted
      = connector.withConnection{ cx => 
          id $ ("id" -> _) $ (Map(_)) $ (mapping[T].fetchByPrimaryKey(_, cx).asInstanceOf[T with Persisted])
        }

    /**
     * Save the entity. An Abstraction over INSERT and UPDATE-queries. Which one to perform will be decided based on whether the [[sorm.Persisted]] trait is mixed in the value you provide.
     * @param value The value to save
     * @return The saved entity instance with a [[sorm.Persisted]] trait mixed in
     */
    def save
      [ T <: AnyRef : TypeTag ]
      ( value : T )
      : T with Persisted
      = connector.withConnection{ cx =>
          mapping[T].save(value, cx).asInstanceOf[T with Persisted]
        }

    /**
     * Saves the entity by overwriting the existing one if one with the matching unique keys exists and creating a new one otherwise. Executing simply [[sorm.Instance.Api#save]] in a situation of unique keys clash would have thrown an exception. Beware that in case when not all unique keys are matched this method will still throw an exception.
     * @param value The value to save
     * @return The saved entity instance with a [[sorm.Persisted]] trait mixed in
     */
    def saveByUniqueKeys
      [ T <: AnyRef : TypeTag ]
      ( value : T )
      : T with Persisted
      = (mapping[T].uniqueKeys.flatten zipBy value.reflected.propertyValue)
          //  todo: check the unique entities
          .ensuring(_.nonEmpty, "Type doesn't have unique keys")
          .foldLeft(query){ case (q, (n, v)) => q.whereEqual(n, v) }
          .$(q =>
            connector.withConnection{ cx =>
              cx.transaction {
                q.fetchOneId()
                  .map(Persisted(value, _))
                  .getOrElse(value)
                  .$(mapping[T].save(_, cx).asInstanceOf[T with Persisted])
              }
            }
          )

    /**
     * Delete a persisted entity
     * @param value The entity
     * @tparam T The entity
     */
    def delete
      [ T <: AnyRef : TypeTag ]
      ( value : T )
      = connector.withConnection{ cx => mapping[T].delete(value, cx) }

    /**
     * Perform several db-requests in a single transaction. For most dbs this provides guarantees that nothing will be changed in between the db-requests in multithreaded applications and that it will roll-back in case of any failure.
     *
     * All db-requests which should be executed as part of this transaction must be run on the same thread this method gets called on.
     *
     * Use transactions with care because for the time the transaction is being executed the involved tables are supposed to get locked, putting all the requests to them from other threads in a queue until the current transaction finishes. The best practice is to make transactions as short as possible and to perform any calculations prior to entering transaction.
     * 
     * @param t The closure wrapping the actions performed in a single transaction.
     * @tparam T The result of the closure
     * @return The result of the last statement of the passed in closure
     */
    def transaction [ T ] ( t : => T ) : T
      = connector.withConnection{ cx => cx.transaction(t) }

    /**
     * Current time at DB server in milliseconds. Effectively fetches the date only once to calculate the deviation.
     */
    lazy val nowMillis = connector.withConnection { cx =>
      val deviation = System.currentTimeMillis() - cx.now().getMillis
      () => System.currentTimeMillis() - deviation
    }

    /**
     * Current DateTime at DB server. Effectively fetches the date only once to calculate the deviation.
     */
    def now() = new DateTime(nowMillis())

    /**
     * Free all the underlying resources. Useful in multi-instance tests
     */
    def close() = connector.close()
  }
  abstract class Initialization
    ( entities : Traversable[Entity],
      url : String,
      user : String = "",
      password : String = "",
      poolSize : Int = 1,
      initMode : InitMode = InitMode.Create )
    extends Logging
  {
    import core.Initialization._

    protected val connector = new Connector(url, user, password, poolSize)

    //  Validate entities (must be prior to mappings creation due to possible mappingkind detection errors):
    entities flatMap errors map (new ValidationException(_)) foreach (throw _)

    protected val mappings
      = {
        val settings
          = entities.view
              .map{ e =>
                e.reflection -> EntitySettings(e.indexed, e.unique)
              }
              .toMap

        settings.keys
          .zipBy{ new EntityMapping(_, None, settings) }
          .toMap
      }

    // Validate input:
    mappings.values.toStream $ errors map (new ValidationException(_)) foreach (throw _)

    // Initialize a db schema:
    initializeSchema(mappings.values, connector, initMode)

    // Precache persisted classes (required for multithreading)
    entities.foreach(_.reflection $ PersistedClass.apply)

  }
  class ValidationException ( m : String ) extends SormException(m)

}