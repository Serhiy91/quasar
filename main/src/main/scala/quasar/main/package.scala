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

package quasar

import quasar.Predef._

import quasar.config.ConfigOps
import quasar.effect._
import quasar.fp._
import quasar.fp.free._
import quasar.fs._
import quasar.fs.mount._
import quasar.fs.mount.hierarchical._
import quasar.physical.mongodb._

import argonaut.EncodeJson
import com.mongodb.MongoException
import monocle.Lens
import scalaz.{Failure => _, Lens => _, _}, Scalaz._
import scalaz.concurrent.Task

/** Concrete effect types and their interpreters that implement the quasar
  * functionality.
  */
package object main {
  import FileSystemDef.DefinitionResult
  import QueryFile.ResultHandle
  import Mounting.PathTypeMismatch

  type MainErrT[F[_], A] = EitherT[F, String, A]
  type MainTask[A]       = MainErrT[Task, A]

  val MainTask           = MonadError[EitherT[Task,String,?], String]

  /** Effects that physical filesystems require.
    */
  type PhysFsEff[A]  = Coproduct[MongoErr, Task, A]
  type PhysFsEffM[A] = Free[PhysFsEff, A]

  object PhysFsEff {
    // Lift into FsErrsIOM
    val toFsErrsIOM: PhysFsEff ~> FsErrsIOM =
      injectFT[MongoErr, FsErrsIO] :+:
      injectFT[Task, FsErrsIO]
  }

  /** The physical filesystems currently supported.
    *
    * NB: Will eventually be the lcd of all physical filesystems, or we limit
    * to a fixed set of effects that filesystems must interpret into.
    */
  val physicalFileSystems: FileSystemDef[PhysFsEffM] =
    quasar.physical.mongodb.fs.mongoDbFileSystemDef[PhysFsEff]

  /** The intermediate effect FileSystem operations are interpreted into.
    */
  type FsEff0[A] = Coproduct[ViewState, MountedResultH, A]
  type FsEff1[A] = Coproduct[MonotonicSeq, FsEff0, A]
  type FsEff2[A] = Coproduct[PhysFsEffM, FsEff1, A]
  type FsEff[A]  = Coproduct[MountConfigs, FsEff2, A]
  type FsEffM[A] = Free[FsEff, A]

  object FsEff {
    /** Interpret all effects except failures. */
    def toFsErrsIOM(
      seqRef: TaskRef[Long],
      viewHandlesRef: TaskRef[ViewHandles],
      mntResRef: TaskRef[Map[ResultHandle, (ADir, ResultHandle)]],
      mntCfgsT: MountConfigs ~> Task
    ): FsEff ~> FsErrsIOM = {
      def injTask[E[_]](f: E ~> Task): E ~> FsErrsIOM =
        injectFT[Task, FsErrsIO] compose f

      injTask[MountConfigs](mntCfgsT)                               :+:
      foldMapNT(PhysFsEff.toFsErrsIOM)                              :+:
      injTask[MonotonicSeq](MonotonicSeq.fromTaskRef(seqRef))       :+:
      injTask[ViewState](KeyValueStore.fromTaskRef(viewHandlesRef)) :+:
      injTask[MountedResultH](KeyValueStore.fromTaskRef(mntResRef))
    }

    /** A dynamic `FileSystem` evaluator formed by internally fetching an
      * interpreter from a `TaskRef`, allowing for the behavior to change over
      * time as the ref is updated.
      */
    def evalFSFromRef[S[_]](
      ref: TaskRef[FileSystem ~> FsEffM],
      f: FsEff ~> Free[S, ?]
    )(implicit S: Task :<: S): FileSystem ~> Free[S, ?] = {
      type F[A] = Free[S, A]
      new (FileSystem ~> F) {
        def apply[A](fs: FileSystem[A]) =
          injectFT[Task, S].apply(ref.read.map(free.foldMapNT[FsEff, F](f) compose _))
            .flatMap(_.apply(fs))
      }
    }
  }

  /** The error effects invovled in FileSystem evaluation.
    *
    * We interpret into this effect to defer error handling based on the
    * final context of interpretation (i.e. web service vs cmd line).
    */
  // TODO: Add MountConfigs to this type
  type FsErrsIO[A] = Coproduct[MongoErr, Task, A]
  type FsErrsIOM[A] = Free[FsErrsIO, A]


  //--- Composite FileSystem ---

  /** Provides the mount handlers to update the composite
    * (view + hierarchical + physical) filesystem whenever a mount is added
    * or removed.
    */
  val mountHandler = EvaluatorMounter[PhysFsEffM, FsEff](physicalFileSystems)
  import mountHandler.EvalFSRef

  /** An atomic reference holding the mapping between mount points and
    * physical filesystem interpreters.
    */
  type MountedFs[A]  = AtomicRef[Mounts[DefinitionResult[PhysFsEffM]], A]

  /** Effect required by the composite (view + hierarchical + physical)
    * filesystem.
    */
  type CompFsEff0[A] = Coproduct[MountedFs, MountConfigs, A]
  type CompFsEff1[A] = Coproduct[PhysFsEffM, CompFsEff0, A]
  type CompFsEff[A]  = Coproduct[EvalFSRef, CompFsEff1, A]
  type CompFsEffM[A] = Free[CompFsEff, A]

  object CompFsEff {
    def toFsErrsIOM(
      evalRef: TaskRef[FileSystem ~> FsEffM],
      mntsRef: TaskRef[Mounts[DefinitionResult[PhysFsEffM]]],
      mntCfgsT: MountConfigs ~> Task
    ): CompFsEff ~> FsErrsIOM = {
      def injTask[E[_]](f: E ~> Task): E ~> FsErrsIOM =
        injectFT[Task, FsErrsIO] compose f

      injTask[EvalFSRef](AtomicRef.fromTaskRef(evalRef)) :+:
      foldMapNT(PhysFsEff.toFsErrsIOM)                   :+:
      injTask[MountedFs](AtomicRef.fromTaskRef(mntsRef)) :+:
      injTask[MountConfigs](mntCfgsT)
    }
  }

  /** Effect required by the "complete" filesystem supporting modifying mounts,
    * views, hierarchical mounts and physical implementations.
    */
  type CompleteFsEff[A] = Coproduct[MountConfigs, CompFsEffM, A]
  type CompleteFsEffM[A] = Free[CompleteFsEff, A]

  val mounter: Mounting ~> CompleteFsEffM =
    quasar.fs.mount.Mounter[CompFsEffM, CompleteFsEff](
      mountHandler.mount[CompFsEff](_),
      mountHandler.unmount[CompFsEff](_))

  /** Effect representing fully interpreting everything but `MountConfigs` to
    * allow for multiple implementations.
    */
  type MntCfgsIO[A] = Coproduct[MountConfigs, Task, A]
  type MntCfgsIOM[A] = Free[MntCfgsIO, A]

  // TODO: MntCgsIO doesn't look terribly useful any longer, maybe just a
  //       forgetful interpreter for MountConfigs is needed?
  object MntCfgsIO {
    /** Interprets `MountConfigsF` in memory, without any persistence to a
      * backing store.
      */
    val ephemeral: MntCfgsIO ~> Task = {
      type ST[A] = StateT[Task, Map[APath, MountConfig], A]

      val toState: MountConfigs ~> ST =
        KeyValueStore.toState[ST](Lens.id[Map[APath, MountConfig]])

      val interpret: MntCfgsIO ~> ST =
        toState :+: liftMT[Task, StateT[?[_], Map[APath, MountConfig], ?]]

      evalNT[Task, Map[APath, MountConfig]](Map()) compose interpret
    }

    /** Interprets `MountConfigsF`, persisting changes via the write interpreter. */
    def durable[C: EncodeJson](write: MountConfigs ~> Task): MntCfgsIO ~> Task =
      write :+: NaturalTransformation.refl
  }

  /** Encompasses all the failure effects and mount config effect, all of
    * which we need to evaluate using more than one implementation.
    */
  type CfgsErrsIO0[A] = Coproduct[MongoErr, MntCfgsIO, A]
  type CfgsErrsIO[A]  = Coproduct[FileSystemFailure, CfgsErrsIO0, A]
  type CfgsErrsIOM[A] = Free[CfgsErrsIO, A]

  object CfgsErrsIO {
    /** Interprets errors into strings. */
    def toMainTask(evalCfgsIO: MntCfgsIO ~> Task): CfgsErrsIO ~> MainTask = {
      val f =
        Failure.toRuntimeError[Task,FileSystemError] :+:
        Failure.toCatchable[Task,MongoException]     :+:
        evalCfgsIO

      new (CfgsErrsIO ~> MainTask) {
        def apply[A](a: CfgsErrsIO[A]) =
          EitherT(f(a).attempt).leftMap(_.getMessage)
      }
    }
  }

  /** Effect required by the core Quasar services */
  type CoreEff0[A] = Coproduct[Mounting, FileSystem, A]
  type CoreEff1[A] = Coproduct[FileSystemFailure, CoreEff0, A]
  type CoreEff2[A] = Coproduct[MountConfigs, CoreEff1, A]
  type CoreEff[A]  = Coproduct[Task, CoreEff2, A]
  type CoreEffM[A] = Free[CoreEff, A]

  // TODO: Don't interpret MountConfigs anywhere.
  // Accept an initial set of mounts?
  object CoreEff {
    def interpreter[C: EncodeJson: ConfigOps]
      (mntCfgsT: MountConfigs ~> Task)
      : Task[CoreEff ~> CfgsErrsIOM] =
      for {
        startSeq   <- Task.delay(scala.util.Random.nextInt.toLong)
        seqRef     <- TaskRef(startSeq)
        viewHRef   <- TaskRef[ViewHandles](Map())
        mntedRHRef <- TaskRef(Map[ResultHandle, (ADir, ResultHandle)]())
        evalFsRef  <- TaskRef(Empty.fileSystem[FsEffM])
        mntsRef    <- TaskRef(Mounts.empty[DefinitionResult[PhysFsEffM]])
      } yield {
        val f: FsEff ~> FsErrsIOM = FsEff.toFsErrsIOM(seqRef, viewHRef, mntedRHRef, mntCfgsT)
        val g: CompFsEff ~> FsErrsIOM = CompFsEff.toFsErrsIOM(evalFsRef, mntsRef, mntCfgsT)

        val liftTask: Task ~> CfgsErrsIOM =
          injectFT[Task, CfgsErrsIO]

        val translateFsErrs: FsErrsIOM ~> CfgsErrsIOM =
          free.foldMapNT[FsErrsIO, CfgsErrsIOM](
            injectFT[MongoErr, CfgsErrsIO] :+: liftTask)

        val mnt: CompleteFsEff ~> CfgsErrsIOM =
          injectFT[MountConfigs, CfgsErrsIO] :+:
          translateFsErrs.compose[CompFsEffM](free.foldMapNT(g))

        val mounting: Mounting ~> CfgsErrsIOM =
          free.foldMapNT(mnt) compose mounter

        liftTask                                :+:
        injectFT[MountConfigs, CfgsErrsIO]      :+:
        injectFT[FileSystemFailure, CfgsErrsIO] :+:
        mounting                                :+:
        (translateFsErrs compose FsEff.evalFSFromRef(evalFsRef, f))
      }
  }

  /** Mount all the mounts defined in the given configuration. */
  def mountAll[S[_]]
      (mc: MountingsConfig)
      (implicit mnt: Mounting.Ops[S])
      : Free[S, String \/ Unit] = {

    type MainF[A] = EitherT[mnt.F, String, A]

    def toMainF(v: mnt.M[PathTypeMismatch \/ Unit]): MainF[Unit] =
      EitherT[mnt.F, String, Unit](
        v.fold(_.shows.left, _.fold(_.shows.left, _.right)))

    mc.toMap.toList.traverse_ { case (p, cfg) => toMainF(mnt.mount(p, cfg)) }.run
  }
}
