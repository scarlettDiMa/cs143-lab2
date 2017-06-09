/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution

import org.apache.spark.sql.catalyst.errors._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.physical.{ClusteredDistribution, AllTuples, UnspecifiedDistribution}
import org.apache.spark.util.collection.SizeTrackingAppendOnlyMap

case class SpillableAggregate(
                               partial: Boolean,
                               groupingExpressions: Seq[Expression],
                               aggregateExpressions: Seq[NamedExpression],
                               child: SparkPlan) extends UnaryNode {

  override def requiredChildDistribution =
    if (partial) {
      UnspecifiedDistribution :: Nil
    } else {
      if (groupingExpressions == Nil) {
        AllTuples :: Nil
      } else {
        ClusteredDistribution(groupingExpressions) :: Nil
      }
    }

  override def output = aggregateExpressions.map(_.toAttribute)

  /**
    * An aggregate that needs to be computed for each row in a group.
    *
    * @param unbound Unbound version of this aggregate, used for result substitution.
    * @param aggregate A bound copy of this aggregate used to create a new aggregation buffer.
    * @param resultAttribute An attribute used to refer to the result of this aggregate in the final
    *                        output.
    */
  case class ComputedAggregate(
                                unbound: AggregateExpression,
                                aggregate: AggregateExpression,
                                resultAttribute: AttributeReference)


  /** A list of aggregates that need to be computed for each group. */
  private[this] val computedAggregates = aggregateExpressions.flatMap { agg =>
    agg.collect {
      case a: AggregateExpression =>
        ComputedAggregate(
          a,
          BindReferences.bindReference(a, child.output),
          AttributeReference(s"aggResult:$a", a.dataType, a.nullable)())
    }
  }.toArray

  /** Physical aggregator generated from a logical expression.  */
  private[this] val aggregator: ComputedAggregate = computedAggregates(0)
  /** Schema of the aggregate.  */
  private[this] val aggregatorSchema: AttributeReference = aggregator.resultAttribute

  /** Creates a new aggregator instance.  */
  private[this] def newAggregatorInstance(): AggregateFunction = aggregator.aggregate.newInstance()

  /** Named attributes used to substitute grouping attributes in the final result. */
  private[this] val namedGroups = groupingExpressions.map {
    case ne: NamedExpression => ne -> ne.toAttribute
    case e => e -> Alias(e, s"groupingExpr:$e")().toAttribute
  }

  /**
    * A map of substitutions that are used to insert the aggregate expressions and grouping
    * expression into the final result expression.
    */
  protected val resultMap =
  ( Seq(aggregator.unbound -> aggregator.resultAttribute) ++ namedGroups).toMap

  /**
    * Substituted version of aggregateExpressions expressions which are used to compute final
    * output rows given a group and the result of all aggregate computations.
    */
  private[this] val resultExpression = aggregateExpressions.map(agg => agg.transform {
    case e: Expression if resultMap.contains(e) => resultMap(e)
  }
  )

  override def execute() = attachTree(this, "execute") {///should be same as aggregate
    child.execute().mapPartitions(iter => generateIterator(iter))
  }

  /**
    * This method takes an iterator as an input. The iterator is drained either by aggregating
    * values or by spilling to disk. Spilled partitions are successively aggregated one by one
    * until no data is left.
    *
    * @param input the input iterator
    * @param memorySize the memory size limit for this aggregate
    * @return the result of applying the projection
    */
  def generateIterator(input: Iterator[Row], memorySize: Long = 64 * 1024 * 1024, numPartitions: Int = 64): Iterator[Row] = {
    val groupingProjection = CS143Utils.getNewProjection(groupingExpressions, child.output)
    var currentAggregationTable = new SizeTrackingAppendOnlyMap[Row, AggregateFunction]
    var data = input

    def initSpills(): Array[DiskPartition]  = {
      //initialize partitions with size 0 and number of partitions = numPartitions
      /* IMPLEMENT THIS METHOD */
      //blockSize = 0
      // create empty partitions
      val partitionarray: Array[DiskPartition] = new Array[DiskPartition](numPartitions)
      for (i <- 0 to numPartitions - 1) {
        partitionarray(i) = new DiskPartition(i.toString, 0)
      }
      // return the array of partitions
      partitionarray
    }

    val spills = initSpills()

    new Iterator[Row] {///should be same as aggregate
      var aggregateResult: Iterator[Row] = aggregate()

      val partitionIterator = spills.iterator

      def hasNext() = {
        // IMPLEMENT ME
        //val result = aggregateResult.hasNext //part 6
        val result = aggregateResult.hasNext || (partitionIterator.hasNext && fetchSpill())
        if (!result) {
          // close each DiskPartition
          // remove partition temporary files
          for (thispartition <- spills) {
            thispartition.closePartition()
          }
        }
        result
      }

      def next() = {
        // IMPLEMENT ME
        aggregateResult.next()
      }


      /**
        * This method load the aggregation hash table by draining the data iterator
        *
        * @return
        */
      private def aggregate(): Iterator[Row] = {
        /* IMPLEMENT THIS METHOD */
        var thisRow: Row = null
        while (data.hasNext) {
          thisRow = data.next()

          val thisGroup = groupingProjection(thisRow)
          var thisAggregator = currentAggregationTable(thisGroup)
          // if currentaggregator is not in the table
          if (thisAggregator == null) {
            //check if we need to spill to disk
            if (CS143Utils.maybeSpill(currentAggregationTable, memorySize)) {
              spillRecord(thisRow)
            } 
            else {
              //if we do not need to spill to disk, update the table with the new [K,V] pair
              thisAggregator = newAggregatorInstance()
              //currentAggregationTable.update(currentGroup.copy(), thisAggregator)
              currentAggregationTable.update(thisGroup, thisAggregator)

            }
          }
          else {
            //if currentaggregator is in the table already, update the value
            thisAggregator.update(thisRow)
          }
        }

        // close each DiskPartition's input
        // flush memory to disk for each partition
        for (thispartition <- spills) {
          thispartition.closeInput()
        }

        // return Aggregate Result Iterator
        AggregateIteratorGenerator(resultExpression, Seq(aggregatorSchema) ++ namedGroups.map(_._2))(currentAggregationTable.iterator)

      }

      /**
        * Spill input rows to the proper partition using hashing
        *
        * @return
        */
      private def spillRecord(row: Row)  = {
        /* IMPLEMENT THIS METHOD */
        spills(row.hashCode % numPartitions).insert(row)
      }

      /**
        * Global object wrapping the spills into a DiskHashedRelation. This variable is
        * set only when we are sure that the input iterator has been completely drained.
        *
        * @return
        */
      var hashedSpills: Option[Iterator[DiskPartition]] = None

      /**
        * This method fetches the next records to aggregate from spilled partitions or returns false if we
        * have no spills left.
        *
        * @return
        */
      private def fetchSpill(): Boolean  = {
        //false
        // IMPLEMENT ME

        // get row iterator of next Non-Empty partition
        while (!data.hasNext && partitionIterator.hasNext) {
          val thispartition = partitionIterator.next()
          data = thispartition.getData()
        }

        if (!data.hasNext) {
          false
        } 
        else {

          // clear Aggregation Table and aggregateResult
          currentAggregationTable = new SizeTrackingAppendOnlyMap[Row, AggregateFunction]
          aggregateResult = aggregate()

          true
        }
        
      }
    }
  }
}
