"""只检查 Kotlin 语法和独立 SQLite 队列行为；不调用 Gradle、不启动应用、不连接网络。"""
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/autosend"
DAO = (SOURCE / "data/local/AutoSendDao.kt").read_text(encoding="utf-8-sig")
DATABASE = (SOURCE / "data/local/AppDatabase.kt").read_text(encoding="utf-8-sig")
REPOSITORY = (SOURCE / "data/repository/TelegramRepository.kt").read_text(encoding="utf-8-sig")
QUERIES = {
    match[3]: match[1] or match[2]
    for match in re.finditer(r'@Query\((?:"""(.*?)"""|"((?:\\.|[^"\\])*)")\)\s*(?:suspend\s+)?fun\s+(\w+)', DAO, re.S)
}


def migrate(connection):
    migration = DATABASE.split("private val MIGRATION_13_14 =", 1)[1].split("private val MIGRATION_6_7", 1)[0]
    tables = re.search(r'listOf\((.*?)\)\.forEach', migration, re.S)[1]
    backup = re.search(r'database\.execSQL\("(CREATE TABLE IF NOT EXISTS schema_backup.*?)"\)', migration)[1]
    for table in re.findall(r'"(\w+)"', tables):
        connection.execute(backup.replace("$table", table))
    for sql in re.findall(r'database\.execSQL\("(UPDATE scheduled_tasks.*?)"\)', migration):
        connection.execute(sql)
    for sql in re.findall(r'database\.execSQL\("""(.*?)"""\.trimIndent\(\)\)', migration, re.S):
        connection.execute(sql)
    for sql in re.findall(r'database\.execSQL\("(CREATE UNIQUE INDEX.*?)"\)', migration):
        connection.execute(sql)
    for sql in re.findall(r'database\.execSQL\("(ALTER TABLE.*?)"\)', migration):
        connection.execute(sql)
    connection.commit()


class QueueSqlTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="autosend-sql-check-")
        self.path = Path(self.temp.name) / "queue.db"
        self.db = sqlite3.connect(self.path)
        self.db.row_factory = sqlite3.Row
        self.db.execute("CREATE TABLE scheduled_tasks(id INTEGER PRIMARY KEY, isEnabled INTEGER, content TEXT, deliveryChannel TEXT)")
        self.db.execute("INSERT INTO scheduled_tasks VALUES (1, 1, '原任务', 'QQ')")
        self.db.execute("INSERT INTO scheduled_tasks VALUES (2, 1, '旧 Telegram 任务', 'TELEGRAM')")
        self.db.execute("CREATE TABLE task_logs(id INTEGER PRIMARY KEY, status TEXT)")
        self.db.execute("INSERT INTO task_logs VALUES (1, 'FAILED')")
        for table in ["bots", "chats", "qq_bots", "feishu_webhooks"]:
            self.db.execute(f"CREATE TABLE {table}(id INTEGER PRIMARY KEY, name TEXT)")
        migrate(self.db)

    def tearDown(self):
        self.db.close()
        self.temp.cleanup()

    def add(self, key, **changes):
        row = dict(occurrenceKey=key, taskId=1, botId=1, chatId=1, content="消息", parseMode="NONE",
                   occurrenceAt=100, manual=0, state="WAITING", attempts=0, nextAttemptAt=0,
                   leaseUntil=0, lastError=None, messageId=None, sentAt=None)
        row.update(changes)
        self.db.execute(f"INSERT OR IGNORE INTO pending_messages ({','.join(row)}) VALUES ({','.join('?' for _ in row)})", list(row.values()))

    def gate(self, scope, until):
        self.db.execute("INSERT OR REPLACE INTO delivery_gates VALUES (?, ?, 3, '连接异常')", (scope, until))

    def next(self, now=1_000):
        return self.db.execute(QUERIES["nextReadyMessage"], {"now": now}).fetchone()

    def test_migration_preserves_and_backs_up_original_tasks(self):
        self.assertEqual(self.db.execute("SELECT content FROM scheduled_tasks").fetchone()[0], "原任务")
        self.assertEqual(self.db.execute("SELECT content FROM schema_backup_v13_scheduled_tasks").fetchone()[0], "原任务")

    def test_upgrade_keeps_previously_hidden_telegram_tasks_inactive(self):
        self.assertEqual(self.db.execute("SELECT isEnabled FROM scheduled_tasks WHERE id=2").fetchone()[0], 0)
        self.assertEqual(self.db.execute("SELECT isEnabled FROM schema_backup_v13_scheduled_tasks WHERE id=2").fetchone()[0], 1)
        self.assertEqual(self.db.execute("SELECT isEnabled FROM scheduled_tasks WHERE id=1").fetchone()[0], 1)

    def test_occurrence_key_deduplicates_overlapping_triggers(self):
        self.add("1:100")
        self.add("1:100", content="重复触发")
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM pending_messages").fetchone()[0], 1)

    def test_recurring_occurrences_remain_independent(self):
        self.add("1:100")
        self.add("1:200", occurrenceAt=200)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM pending_messages").fetchone()[0], 2)
        self.assertEqual(self.next()["occurrenceKey"], "1:100")

    def test_network_failure_gates_entire_queue(self):
        self.add("a")
        self.add("b", botId=2, chatId=2)
        self.gate("network", 50_000)
        self.assertEqual(self.next()["readyAt"], 50_000)

    def test_target_rate_does_not_block_other_chat(self):
        self.add("older", occurrenceAt=100)
        self.add("other", chatId=2, occurrenceAt=200)
        self.gate("chat:1:1", 5_000)
        self.assertEqual(self.next()["occurrenceKey"], "other")

    def test_rate_limit_applies_to_entire_bot(self):
        self.add("a")
        self.add("b", chatId=2)
        self.gate("bot:1", 20_000)
        self.assertEqual(self.next()["readyAt"], 20_000)

    def test_permanent_bot_failure_does_not_block_other_bot(self):
        self.add("a")
        self.add("b", botId=2)
        self.gate("bot:1", 2**63 - 1)
        self.assertEqual(self.next()["occurrenceKey"], "b")

    def test_blocked_message_does_not_block_normal_message(self):
        self.add("a", state="BLOCKED")
        self.add("b")
        self.assertEqual(self.next()["occurrenceKey"], "b")

    def test_success_and_cancel_are_not_resent(self):
        self.add("a", state="SENT")
        self.add("b", state="CANCELLED")
        self.assertIsNone(self.next())

    def test_pause_survives_retry_gate_reset(self):
        self.add("a")
        self.gate("pause", 2**63 - 1)
        self.gate("network", 50_000)
        self.db.execute(QUERIES["resetBlockedGates"])
        self.assertIsNone(self.next())

    def test_sending_lease_survives_restart(self):
        self.add("a", state="SENDING", leaseUntil=60_000)
        self.db.commit()
        self.db.close()
        self.db = sqlite3.connect(self.path)
        self.db.row_factory = sqlite3.Row
        self.assertEqual(self.next()["readyAt"], 60_000)

    def test_overdue_messages_keep_original_order(self):
        self.add("older", occurrenceAt=100, nextAttemptAt=900)
        self.add("newer", occurrenceAt=200)
        self.assertEqual(self.next()["occurrenceKey"], "older")

    def test_disabled_task_pauses_scheduled_but_allows_manual_messages(self):
        self.db.execute("UPDATE scheduled_tasks SET isEnabled = 0")
        self.add("scheduled")
        self.add("manual", manual=1)
        self.assertEqual(self.next()["occurrenceKey"], "manual")

    def test_deleting_task_preserves_pending_message(self):
        self.add("a")
        self.db.execute(QUERIES["preserveMessagesForDeletedTask"], {"taskId": 1})
        self.db.execute("DELETE FROM scheduled_tasks WHERE id=1")
        self.assertEqual(self.db.execute("SELECT state FROM pending_messages").fetchone()[0], "BLOCKED")

    def test_retry_does_not_revive_deleted_task_messages(self):
        self.add("a", state="BLOCKED")
        self.db.execute("DELETE FROM scheduled_tasks WHERE id=1")
        self.db.execute(QUERIES["retryBlockedMessages"])
        self.assertIsNone(self.next())

    def test_edit_cannot_resurrect_cancelled_or_inflight_message(self):
        self.add("a", state="CANCELLED")
        row = self.db.execute("SELECT id FROM pending_messages").fetchone()
        self.db.execute(QUERIES["editPendingMessage"], {"id": row[0], "content": "编辑"})
        self.assertIsNone(self.next())
        self.db.execute("UPDATE pending_messages SET state='SENDING'")
        self.db.execute(QUERIES["editPendingMessage"], {"id": row[0], "content": "编辑"})
        self.assertEqual(self.db.execute("SELECT content FROM pending_messages").fetchone()[0], "消息")

    def test_retry_does_not_bypass_telegram_rate_limit(self):
        self.add("a")
        self.gate("bot:1", 60_000)
        self.db.execute(QUERIES["resetBlockedGates"])
        self.assertEqual(self.next()["readyAt"], 60_000)

    def test_clearing_logs_does_not_clear_queue(self):
        self.add("a")
        self.db.execute(QUERIES["clearVisibleLogs"])
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM pending_messages").fetchone()[0], 1)

    def backup_qq_task(self, task_id):
        # 执行导入迁移实际使用的 SQL，不接触真实数据库或账号配置。
        create = re.search(r'sql.execSQL\("(CREATE TABLE IF NOT EXISTS telegram_import_qq_backup.*?)"\)', REPOSITORY)[1]
        insert = re.search(r'sql.execSQL\("(INSERT INTO telegram_import_qq_backup.*?)"', REPOSITORY)[1]
        self.db.execute(create)
        self.db.execute(insert, (task_id, task_id))

    def test_qq_import_backup_keeps_first_snapshot_and_task_id(self):
        self.backup_qq_task(1)
        self.db.execute("UPDATE scheduled_tasks SET deliveryChannel='TELEGRAM' WHERE id=1")
        self.backup_qq_task(1)
        rows = self.db.execute("SELECT * FROM telegram_import_qq_backup").fetchall()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["id"], 1)
        self.assertEqual(rows[0]["deliveryChannel"], "QQ")
        self.assertEqual(rows[0]["isEnabled"], 1)
        self.assertEqual(self.db.execute("SELECT id FROM scheduled_tasks WHERE deliveryChannel='TELEGRAM'").fetchone()[0], 1)

    def test_qq_import_backup_and_conversion_rollback_together(self):
        self.db.execute("BEGIN")
        self.backup_qq_task(1)
        self.db.execute("UPDATE scheduled_tasks SET deliveryChannel='TELEGRAM' WHERE id=1")
        self.db.rollback()
        self.assertEqual(self.db.execute("SELECT deliveryChannel FROM scheduled_tasks WHERE id=1").fetchone()[0], "QQ")
        self.assertIsNone(self.db.execute("SELECT name FROM sqlite_master WHERE name='telegram_import_qq_backup'").fetchone())


JAVA_PARSER = r'''
import java.nio.file.*;
import org.jetbrains.kotlin.cli.jvm.compiler.*;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.KtPsiFactory;
class KotlinSyntaxCheck {
    public static void main(String[] args) throws Exception {
        var disposable = Disposer.newDisposable();
        try {
            var env = KotlinCoreEnvironment.createForProduction(disposable, new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES);
            var factory = new KtPsiFactory(env.getProject(), false);
            int failures = 0;
            for (String arg : args) {
                var path = Path.of(arg);
                var source = Files.readString(path);
                var file = factory.createFile(path.getFileName().toString(), source);
                for (var error : PsiTreeUtil.collectElementsOfType(file, PsiErrorElement.class)) {
                    int line = 1 + (int) source.substring(0, error.getTextOffset()).chars().filter(c -> c == '\n').count();
                    System.err.println(arg + ":" + line + " " + error.getErrorDescription());
                    failures++;
                }
            }
            if (failures > 0) System.exit(1);
            System.out.println("Kotlin syntax: " + args.length + " files passed (no type checking or Android build)");
        } finally { Disposer.dispose(disposable); }
    }
}
'''.replace("c == '\\\\n'", "c == '\\n'")


def check_kotlin_syntax():
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    jars = []
    for group, artifact, version in [("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "1.9.23"),
                                    ("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.23"),
                                    ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330")]:
        candidates = list((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
        if not candidates:
            raise RuntimeError(f"Missing cached {artifact} {version}; no downloads performed")
        jars.append(str(candidates[0]))
    files = sorted(str(path) for path in SOURCE.rglob("*.kt"))
    with tempfile.TemporaryDirectory(prefix="autosend-kotlin-syntax-") as directory:
        source = Path(directory) / "KotlinSyntaxCheck.java"
        source.write_text(JAVA_PARSER, encoding="utf-8")
        subprocess.run([shutil.which("java"), "-cp", os.pathsep.join(jars), str(source), *files], check=True)


if __name__ == "__main__":
    result = unittest.TextTestRunner(verbosity=1).run(unittest.defaultTestLoader.loadTestsFromTestCase(QueueSqlTests))
    if not result.wasSuccessful():
        raise SystemExit(1)
    check_kotlin_syntax()
