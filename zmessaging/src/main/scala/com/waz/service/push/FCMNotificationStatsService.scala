/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service.push

import com.waz.content.Database
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.log.LogSE._
import com.waz.model.Uid
import com.waz.service.push.FCMNotificationStatsRepository.FCMNotificationStatsDao
import com.waz.service.push.FCMNotificationsRepository.FCMNotificationsDao
import com.waz.utils.wrappers.DB
import org.threeten.bp.Instant
import org.threeten.bp.temporal.ChronoUnit._

import scala.concurrent.{ExecutionContext, Future}

trait FCMNotificationStatsService {
  def storeNotificationState(id: Uid, stage: String, timestamp: Instant)
                            (implicit ec: ExecutionContext): Future[Unit]
  def getStats(implicit db: DB): Vector[FCMNotificationStats]
}

class FCMNotificationStatsServiceImpl()(implicit db: Database)
  extends FCMNotificationStatsService with DerivedLogTag{

  import FCMNotificationStatsService.updateBucketWithNotification

  private def getPreviousStageTime(id: Uid, stage: String)(implicit db: DB): Option[Instant] = {
    FCMNotificationsRepository.prevStage(stage) match {
      case Some(s) => FCMNotificationsDao.getById(id, s).map(_.receivedAt) //TODO: rename receivedAt
      case _ =>
        None
    }
  }

  override def storeNotificationState(id: Uid, stage: String, timestamp: Instant)
                                     (implicit ec: ExecutionContext): Future[Unit] =
    db.withTransaction { implicit db =>
      FCMNotificationsDao.insertOrIgnore(FCMNotification(id, timestamp, stage))
      FCMNotificationStatsDao.getById(stage) match {
        case Some(stageRow) =>
          getPreviousStageTime(id, stage) match {
            case Some(prev) =>
              FCMNotificationStatsDao
                .insertOrReplace(updateBucketWithNotification(stageRow, timestamp, stage, prev))
            case None =>
              error(l"""Couldn't find prev stage for notification $id at stage
                    ${showString(stage)}""")
          }
        case _ =>
          error(l"Failed to find row for stage ${showString(stage)}, skipping notification...")
      }

    }.future.map(_ => ())

  override def getStats(implicit db: DB): Vector[FCMNotificationStats] =
    FCMNotificationStatsDao.list
}

object FCMNotificationStatsService {

  //this method only exists to facilitate testing
  def updateBucketWithNotification(curStats: FCMNotificationStats, stageTimestamp: Instant,
                                   stage: String, prevStageTime: Instant): FCMNotificationStats = {
    val bucket1 = Instant.from(prevStageTime).plus(10, SECONDS)
    val bucket2 = Instant.from(prevStageTime).plus(30, MINUTES)
    if (stageTimestamp.isBefore(bucket1))
      curStats.copy(stage, bucket1 = curStats.bucket1 + 1)
    else if (stageTimestamp.isBefore(bucket2))
      curStats.copy(stage, bucket2 = curStats.bucket2 + 1)
    else curStats.copy(stage, bucket3 = curStats.bucket3 + 1)
  }
}
