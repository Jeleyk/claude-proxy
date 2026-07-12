package org.claudeproxy.repo

import org.claudeproxy.db.AccountGroups
import org.claudeproxy.db.Accounts
import org.claudeproxy.db.UserGroupAccess
import org.claudeproxy.model.AccountGroupDto
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

object GroupRepo {

    fun list(): List<AccountGroupDto> = transaction {
        val counts = HashMap<Int, Int>()
        Accounts.selectAll().forEach { row ->
            row[Accounts.groupId]?.let { counts[it] = (counts[it] ?: 0) + 1 }
        }
        AccountGroups.selectAll().map { row ->
            val id = row[AccountGroups.id]
            AccountGroupDto(id, row[AccountGroups.name], counts[id] ?: 0, row[AccountGroups.createdAt].toString())
        }
    }

    fun create(name: String): Int = transaction {
        AccountGroups.insert {
            it[AccountGroups.name] = name
            it[createdAt] = Instant.now()
        }[AccountGroups.id]
    }

    fun rename(id: Int, name: String) = transaction {
        AccountGroups.update({ AccountGroups.id eq id }) { it[AccountGroups.name] = name }
    }

    fun delete(id: Int): Boolean = transaction {
        // accounts.group_id is ON DELETE SET NULL; user access rows cascade.
        UserGroupAccess.deleteWhere { groupId eq id }
        AccountGroups.deleteWhere { AccountGroups.id eq id } > 0
    }
}
