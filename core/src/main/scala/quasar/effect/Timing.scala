/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.effect

import quasar.Predef._

import org.threeten.bp.{Duration, Instant}
import scalaz._
import scalaz.concurrent.Task

sealed trait Timing[A]
object Timing {
  final case object Timestamp extends Timing[Instant]
  final case object Nanos extends Timing[Long]

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.NonUnitStatements"))
  final class Ops[S[_]](implicit S: Timing :<: S)
    extends LiftedOps[Timing, S] {

    private val lowLevel = LowLevel[S]

    /** Clock time, convertible to time and date, but with relatively little
      * precision.
      */
    def timestamp: F[Instant] =
      lift(Timestamp)

    /** Elapsed time to evaluate some term, with greater precision. */
    def time[A](fa: F[A]): F[(A, Duration)] = {
      for {
        start <- lowLevel.nanos
        a     <- fa
        end   <- lowLevel.nanos
      } yield (a, Duration.ofNanos(end - start))
    }
  }

  object Ops {
    implicit def apply[S[_]](implicit S: Timing :<: S): Ops[S] =
      new Ops[S]
  }

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.NonUnitStatements"))
  final class LowLevel[S[_]](implicit S: Timing :<: S)
    extends LiftedOps[Timing, S] {

    /** Raw nanoseconds value; note that these values are only meaningful when
      * compared to each other.
      */
    val nanos: F[Long] =
      lift(Nanos)
  }

  object LowLevel {
    implicit def apply[S[_]](implicit S: Timing :<: S): LowLevel[S] =
      new LowLevel[S]
  }

  /** Uses the JVM's `currentTimeMillis` and `nanoTime` primitives, providing timestamps
    * with ~10ms resolution and elapsed times accurate to probably ~1 microsecond.
    */
  val toTask: Timing ~> Task =
    new (Timing ~> Task) {
      def apply[A](t: Timing[A]): Task[A] =
        t match {
          case Timestamp => Task.delay { Instant.now }
          case Nanos     => Task.delay { java.lang.System.nanoTime }
        }
    }
}
