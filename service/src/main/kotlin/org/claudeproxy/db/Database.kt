package org.claudeproxy.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.claudeproxy.Config
import org.claudeproxy.model.Permission
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

private val log = LoggerFactory.getLogger("Database")

object Db {
    lateinit var database: Database
        private set

    fun init(config: Config) {
        val hikari = if (config.databaseUrl.isNotBlank()) {
            // PostgreSQL — proper concurrent writes, no lock contention.
            HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                driverClassName = "org.postgresql.Driver"
                username = config.databaseUser
                password = config.databasePassword
                maximumPoolSize = 10
            }
        } else {
            // SQLite fallback. Pragmas in the URL apply to every pooled connection so
            // writers wait for the lock (busy_timeout) instead of failing with SQLITE_BUSY.
            File(config.dbPath).absoluteFile.parentFile?.mkdirs()
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${config.dbPath}?journal_mode=WAL&synchronous=NORMAL&busy_timeout=15000&foreign_keys=on"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 4
                connectionInitSql = "PRAGMA busy_timeout=15000;"
            }
        }
        val ds = HikariDataSource(hikari)
        database = Database.connect(ds)

        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES)
            seedRoles()
            seedAdmin(config)
        }
        org.claudeproxy.repo.ModelPriceRepo.seedDefaults()
        log.info("Database ready ({})", if (config.databaseUrl.isNotBlank()) "PostgreSQL" else "SQLite ${config.dbPath}")
    }

    /** Default roles bundling permissions. Idempotent. */
    private fun seedRoles() {
        val defaults = mapOf(
            "admin" to Permission.entries.toList(),
            "manager" to listOf(Permission.ACCOUNTS_MANAGE, Permission.ACCOUNTS_VIEW, Permission.ACCOUNTS_OWN_MANAGE, Permission.ACCOUNTS_ORDER_TOGGLE, Permission.STATS_VIEW, Permission.STATS_VIEW_OWN, Permission.PROXY_USE),
            "viewer" to listOf(Permission.ACCOUNTS_VIEW, Permission.STATS_VIEW, Permission.STATS_VIEW_OWN),
            "user" to listOf(Permission.PROXY_USE, Permission.STATS_VIEW_OWN, Permission.ACCOUNTS_OWN_MANAGE, Permission.ACCOUNTS_ORDER_TOGGLE),
        )
        defaults.forEach { (roleName, perms) ->
            val roleId = Roles.select(Roles.id).where { Roles.name eq roleName }.firstOrNull()?.get(Roles.id)
                ?: Roles.insert { it[name] = roleName }[Roles.id]
            perms.forEach { p ->
                RolePermissions.insertIgnore {
                    it[RolePermissions.roleId] = roleId
                    it[permission] = p.name
                }
            }
        }
    }

    /** Create the bootstrap admin user from config if no users exist yet. */
    private fun seedAdmin(config: Config) {
        val hasUsers = Users.selectAll().limit(1).any()
        if (hasUsers) return
        val uid = Users.insert {
            it[username] = config.adminUser
            it[passwordHash] = org.claudeproxy.auth.Passwords.hash(config.adminPassword)
            it[enabled] = true
            it[createdAt] = Instant.now()
        }[Users.id]
        val adminRoleId = Roles.select(Roles.id).where { Roles.name eq "admin" }.first()[Roles.id]
        UserRoles.insert {
            it[userId] = uid
            it[roleId] = adminRoleId
        }
        log.warn("Bootstrapped admin user '{}' (change the password!)", config.adminUser)
    }
}
