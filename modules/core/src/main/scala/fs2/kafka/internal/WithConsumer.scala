/*
 * Copyright 2018-2021 OVO Energy Limited
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package fs2.kafka.internal

import cats.effect.{Async, Resource}
import cats.effect.std.Semaphore
import cats.implicits._
import fs2.kafka.consumer.MkConsumer
import fs2.kafka.{ConsumerSettings, KafkaByteConsumer}
import fs2.kafka.internal.syntax._

private[kafka] sealed abstract class WithConsumer[F[_]] {
  def apply[A](f: (KafkaByteConsumer, Blocking[F]) => F[A]): F[A]

  def blocking[A](f: KafkaByteConsumer => A): F[A] = apply {
    case (producer, blocking) => blocking(f(producer))
  }
}

private[kafka] object WithConsumer {
  def apply[F[_], K, V](
    settings: ConsumerSettings[F, K, V],
    mk: MkConsumer[F]
  )(
    implicit F: Async[F]
  ): Resource[F, WithConsumer[F]] =
    Resource.make {
      (mk(settings), Semaphore[F](1L))
        .mapN { (consumer, semaphore) =>
          new WithConsumer[F] {
            override def apply[A](f: (KafkaByteConsumer, Blocking[F]) => F[A]): F[A] =
              semaphore.permit.use { _ =>
                f(consumer, Blocking[F])
              }
          }
        }
    }(_.blocking { _.close(settings.closeTimeout.asJava) })
}
