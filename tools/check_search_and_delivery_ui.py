"""隔离验证搜索、队列展示状态及日志合并；不运行 Gradle、Android、网络或真实数据库。"""
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True

from check_telegram_api import SOURCE, jar
from check_telegram_queue import QUERIES


ROOM = """
package androidx.room
annotation class Entity(val tableName: String, val indices: Array<Index> = [])
annotation class Index(val value: Array<String>, val unique: Boolean = false)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
annotation class Embedded
"""

# 只提供 ICU 默认读音的固定输入，故意把“行”转为 xing；不冒充 Android ICU 运行验证。
ICU = """
package android.icu.text
class Transliterator {
    fun transliterate(value: String): String = value.map {
        val reading = mapOf('银' to "yin", '行' to "xing", '重' to "zhong", '庆' to "qing",
            '长' to "chang", '春' to "chun", '沙' to "sha", '提' to "ti", '醒' to "xing")[it]
        if (reading == null) it.toString() else " $reading "
    }.joinToString("")
    companion object { fun getInstance(@Suppress("UNUSED_PARAMETER") id: String) = Transliterator() }
}
"""

CHECK = """
import com.autosend.utils.*
import com.autosend.data.models.*
import kotlinx.coroutines.runBlocking

private class MemoryLogs : LogDao {
    val rows = mutableListOf<TaskLog>()
    override suspend fun getLogForPendingMessage(pendingMessageId: Long) = rows
        .filter { it.pendingMessageId == pendingMessageId }
        .sortedWith(compareBy<TaskLog> { it.status != LogStatus.SUCCESS }.thenByDescending { it.id }).firstOrNull()
    override suspend fun insertLog(log: TaskLog): Long {
        val id = (rows.maxOfOrNull { it.id } ?: 0) + 1
        rows.add(log.copy(id = id))
        return id
    }
    override suspend fun updateLog(log: TaskLog) { rows[rows.indexOfFirst { it.id == log.id }] = log }
}

fun main() = runBlocking {
    val bank = PinyinUtils.buildSearchIndex("银行")
    for (query in listOf("银", "银行", "yin", "yinh", "yinhang", "yh", "YINH", " yIN h ", "yínháng")) {
        check(PinyinUtils.matches(bank, query)) { "Bank query failed: $query" }
    }
    check(!PinyinUtils.matches(bank, "yinhx"))
    check(!PinyinUtils.matches(bank, "yz"))
    check(PinyinUtils.matches(PinyinUtils.buildSearchIndex("重庆"), "chongq"))
    check(PinyinUtils.matches(PinyinUtils.buildSearchIndex("重庆"), "cq"))
    check(PinyinUtils.matches(PinyinUtils.buildSearchIndex("长春"), "changch"))
    check(PinyinUtils.matches(PinyinUtils.buildSearchIndex("提醒", "银行"), "yh"))
    check(!PinyinUtils.matches(PinyinUtils.buildSearchIndex("yin", "hang"), "yinhang"))
    check(PinyinUtils.matches(PinyinUtils.buildSearchIndex("AutoSend 123"), "SEND123"))
    check(PinyinUtils.matches(PinyinUtils.buildSearchIndex("AutoSend 提醒"), "atx"))
    check(PinyinUtils.matches(PinyinUtils.buildSearchIndex("AutoSend 银行"), "ayh"))
    check(PinyinUtils.matches(PinyinUtils.buildSearchIndex("提醒 AutoSend 银行"), "txayh"))
    check(PinyinUtils.matches(null, " "))
    check(!PinyinUtils.matches(null, "x"))
    // 很多多音字也只保存每个字的候选，不能生成 3^80 组整句索引。
    check(PinyinUtils.matches(PinyinUtils.buildSearchIndex("行".repeat(80)), "hang".repeat(80)))
    println("Search: bank prefixes/full pinyin/initials, common polyphones, case, tone, targets and long text passed (ICU fixture)")

    val message = PendingMessage(id = 1, occurrenceKey = "1:100", taskId = 1, botId = 1, chatId = 1,
        content = "test", parseMode = MessageParseMode.NONE, occurrenceAt = 100, attempts = 3, lastError = "network error")
    val network = DeliveryGate("network", 5000, 3, "network error")
    val waiting = pendingQueuePresentation(message, listOf(network), true, 1000)
    check(waiting.status == "等待重试" && waiting.nextCheckAt == 5000L && waiting.connectionProbe && waiting.canRetry)
    val limited = pendingQueuePresentation(message, listOf(network, DeliveryGate("bot:1", 9000)), true, 1000)
    check(limited.nextCheckAt == 9000L && !limited.connectionProbe)
    val unrelated = pendingQueuePresentation(message, listOf(network, DeliveryGate("chat:2:2", Long.MAX_VALUE)), true, 1000)
    check(unrelated.status == "等待重试")
    val paused = pendingQueuePresentation(message, listOf(DeliveryGate("pause", Long.MAX_VALUE)), true, 1000)
    check(paused.status == "已暂停" && paused.nextCheckAt == null && !paused.canRetry)
    check(pendingQueuePresentation(message, emptyList(), false, 1000).status == "已暂停")
    check(pendingQueuePresentation(message.copy(manual = true), emptyList(), false, 1000).canRetry)
    val blocked = pendingQueuePresentation(message.copy(state = PendingMessage.BLOCKED), emptyList(), true, 1000)
    check(blocked.status == "需要处理" && blocked.nextCheckAt == null)
    val bot = pendingQueuePresentation(message, listOf(DeliveryGate("bot:1", Long.MAX_VALUE, lastError = "bad bot")), true, 1000)
    check(bot.status == "需要处理" && bot.error == "bad bot" && bot.nextCheckAt == null)
    val sending = pendingQueuePresentation(message.copy(state = PendingMessage.SENDING), listOf(network), true, 1000)
    check(sending.status == "正在发送" && sending.error == null && !sending.canRetry && !sending.canEdit)
    val deleted = pendingQueuePresentation(message.copy(state = PendingMessage.BLOCKED, lastError = "原任务已删除"), emptyList(), null, 1000)
    check(!deleted.canRetry && !deleted.canEdit && deleted.error == null)
    val ready = pendingQueuePresentation(message.copy(attempts = 0, lastError = null), emptyList(), true, 1000)
    check(ready.status == "排队中" && ready.nextCheckAt == null)
    println("Queue presentation: cooldown/probe/rate limits, pause, disabled/deleted tasks, blocked and sending states passed")

    val dao = MemoryLogs()
    val failed = TaskLog(taskId = 1, taskContent = "same", botName = "Telegram", chatName = "self",
        status = LogStatus.FAILED, errorMessage = "first", timestamp = 100, pendingMessageId = 10)
    dao.savePendingMessageLog(failed)
    val id = dao.rows.single().id
    dao.updateLog(dao.rows.single().copy(isRead = true))
    dao.savePendingMessageLog(failed.copy(timestamp = 200))
    check(dao.rows.single().id == id && dao.rows.single().isRead)
    dao.savePendingMessageLog(failed.copy(errorMessage = "second", timestamp = 300))
    check(dao.rows.single().id == id && !dao.rows.single().isRead)
    dao.savePendingMessageLog(failed.copy(status = LogStatus.SUCCESS, errorMessage = null, timestamp = 400))
    check(dao.rows.size == 1 && dao.rows.single().status == LogStatus.SUCCESS && dao.rows.single().errorMessage == null)
    check(dao.rows.single().id == id && dao.rows.single().timestamp == 400L)
    dao.savePendingMessageLog(failed.copy(timestamp = 500))
    check(dao.rows.single().status == LogStatus.SUCCESS)
    dao.savePendingMessageLog(failed.copy(pendingMessageId = 11))
    check(dao.rows.size == 2)
    println("Log updates: stable ID, retries, read state, success cleanup, late failures and independent occurrences passed")
}
"""


def check_log_queries():
    with sqlite3.connect(":memory:") as db:
        db.execute("CREATE TABLE task_logs(id INTEGER PRIMARY KEY, pendingMessageId INTEGER, status TEXT, timestamp INTEGER)")
        rows = [(1, 10, "FAILED", 100), (2, 10, "FAILED", 200), (3, 10, "SUCCESS", 300),
                (4, 11, "FAILED", 400), (5, None, "SUCCESS", 500), (6, None, "FAILED", 600),
                (7, 10, "FAILED", 700)]
        db.executemany("INSERT INTO task_logs VALUES (?, ?, ?, ?)", rows)
        visible = list(db.execute(QUERIES["getAllLogs"]))
        assert [row[0] for row in visible] == [6, 5, 4, 3], visible
        assert db.execute(QUERIES["getLogForPendingMessage"], {"pendingMessageId": 10}).fetchone()[0] == 3
        assert db.execute(QUERIES["getLogForPendingMessage"], {"pendingMessageId": 11}).fetchone()[0] == 4
        # 合并必须先于 500 条分页限制，不能让大量旧重试淹没其他消息。
        db.executemany("INSERT INTO task_logs VALUES (?, 20, 'FAILED', ?)", [(i, i) for i in range(8, 609)])
        assert len(list(db.execute(QUERIES["getAllLogs"]))) == 5
        assert db.execute("SELECT COUNT(*) FROM task_logs").fetchone()[0] == 608
    print("Log SQL: old duplicates consolidated before LIMIT, success wins, legacy/independent logs and raw rows preserved", flush=True)


def main():
    check_log_queries()
    java = shutil.which("java")
    if not java:
        raise RuntimeError("Missing local JDK; no downloads performed")
    compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "1.9.23"),
                jar("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.23"),
                jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                jar("org.jetbrains", "annotations", "13.0")]
    runtime = [jar("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.23"),
               jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
               jar("org.jetbrains", "annotations", "13.0")]
    dao_source = (SOURCE / "data/local/AutoSendDao.kt").read_text(encoding="utf-8-sig")
    save = re.search(r"    suspend fun savePendingMessageLog\(log: TaskLog\) \{.*?\n    \}", dao_source, re.S)[0]
    # 执行仓库中实际的更新方法，仅隔离 Room 和其余无关 DAO 方法。
    log_dao = "import com.autosend.data.models.TaskLog\ninterface LogDao {\n" + save + """
    suspend fun getLogForPendingMessage(pendingMessageId: Long): TaskLog?
    suspend fun insertLog(log: TaskLog): Long
    suspend fun updateLog(log: TaskLog)
}
"""
    with tempfile.TemporaryDirectory(prefix="autosend-search-ui-check-") as directory:
        temp = Path(directory)
        fixtures = {"Room.kt": ROOM, "Icu.kt": ICU, "Check.kt": CHECK, "LogDao.kt": log_dao,
                    "ParseMode.kt": "package com.autosend.data.models\nenum class MessageParseMode { NONE, MARKDOWN_V2 }"}
        for name, source in fixtures.items():
            (temp / name).write_text(source, encoding="utf-8")
        actual = [SOURCE / name for name in ["utils/PinyinUtils.kt", "utils/PendingQueuePresentation.kt",
                                             "data/models/PendingMessage.kt", "data/models/TaskLog.kt"]]
        output = temp / "classes"
        subprocess.run([java, "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-classpath", os.pathsep.join(runtime), "-d", str(output),
                        *map(str, actual), *(str(temp / name) for name in fixtures)], check=True)
        subprocess.run([java, "-cp", os.pathsep.join([str(output)] + runtime), "CheckKt"], check=True)


if __name__ == "__main__":
    main()
