/*
Copyright 2015 Mfg labs.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.mfglabs
package precepte

import scalaz.{Monad, Semigroup}
import scala.concurrent.{Future, ExecutionContext}
import akka.NotUsed
import akka.stream.FlowShape
import akka.stream.scaladsl._

import corescalaz._

object PreStream {

  type P[T, M, U, A] = Precepte[T, M, U, Future, A]

  // can be NOT tail rec
  def toFlow[T, M, U, A](pre: P[T, M, U, A], parallelism: Int = 1)(
    implicit m: Monad[Future], ex: ExecutionContext, upd: PStateUpdater[T, M, U], sU: Semigroup[U]
  ): Flow[PState[T, M, U], (PState[T, M, U], A), NotUsed] = {
    type PS = PState[T, M, U]

    def step[B](p: P[T, M, U, B])(idx: Int): Flow[PS, (PS, B), NotUsed] =
      p match {
        case Return(a) =>
          Flow[PS].mapAsync(parallelism){ state => Future.successful(state -> a) }

        case Suspend(fa) =>
          Flow[PS].mapAsync(parallelism){ state => fa.map(a => state -> a) }

        case StepMap(fst, fmap, tags)  =>
          Flow[PS].mapAsync(parallelism){ state =>
            val state0 = upd.appendTags(state, tags, idx)
            fst(state0).map { a =>
              fmap(state0, a)
            }
          }

        case SMap(sub, pf) =>
          step(sub)(idx).map { case (state, a) =>
            state -> pf(a)
          }

        case Flatmap(sub, next) =>
          step(sub)(idx).flatMapConcat { case (state, a) =>
            Source.single(state).via(step(next(a))(idx))
          }

        case ap@Apply(pa, pf) =>
          import GraphDSL.Implicits._

          Flow.fromGraph(GraphDSL.create() { implicit b =>
            val bcast = b.add(Broadcast[PS](2))

            val ga: Flow[PS, (PS, ap._A), NotUsed] = step(pa)(idx + 1)
            val gf: Flow[PS, (PS, ap._A => B), NotUsed] = step(pf)(idx + 2)

            val zip = b.add(ZipWith[(PS, ap._A), (PS, ap._A => B), (PS, B)] { case ((ps0, _a), (ps1, f)) =>
              val ps = upd.updateUnmanaged(ps0, sU.append(ps0.unmanaged, ps1.unmanaged))
              ps -> f(_a)
            })

            bcast.out(0) ~> ga ~> zip.in0
            bcast.out(1) ~> gf ~> zip.in1

            FlowShape(bcast.in, zip.out)
          })

        case ps@SubStep(_, _, _) =>
          step(ps.toStepMap)(idx)

        case MapSuspendSubStep(sub, _) =>
          // TODO not enough IMHO... what to do with F?
          step(sub)(idx)
      }

    step(pre)(0)
  }
}
