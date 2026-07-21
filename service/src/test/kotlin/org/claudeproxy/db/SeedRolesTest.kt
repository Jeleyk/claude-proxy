package org.claudeproxy.db

import org.claudeproxy.Config
import org.claudeproxy.model.Permission
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reproduces the "redeploy resets manually configured role permissions" bug: every service
 * start re-ran the role seeding, and insertIgnore resurrected permissions an admin had
 * deliberately removed from the built-in roles.
 */
class SeedRolesTest {

    private fun config(dbPath: String) = Config(
        bindHost = "127.0.0.1", port = 8787, publicDomain = null, dbPath = dbPath,
        masterKey = "test-master-key-32-chars-minimum-xx", sessionSecret = "test-master-key-32-chars-minimum-xx",
        adminUser = "admin", adminPassword = "admin", upstreamBaseUrl = "https://api.anthropic.com",
        publicBaseUrl = "", databaseUrl = "", databaseUser = "claudeproxy", databasePassword = "",
        internalToken = null,
    )

    private fun userRolePerms(): Set<String> = transaction {
        val rid = Roles.selectAll().where { Roles.name eq "user" }.first()[Roles.id]
        RolePermissions.selectAll().where { RolePermissions.roleId eq rid }
            .map { it[RolePermissions.permission] }.toSet()
    }

    @Test
    fun `fresh database seeds the built-in roles with their default permissions`() {
        val dbFile = File.createTempFile("seed-fresh", ".db")
        try {
            Db.init(config(dbFile.absolutePath))
            val perms = userRolePerms()
            assertTrue(Permission.PROXY_USE.name in perms)
            assertTrue(Permission.ROUTING_USE.name in perms)
            assertTrue(Permission.POOL_GLOBAL_USE.name in perms)
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun `restart does not resurrect permissions an admin removed`() {
        val dbFile = File.createTempFile("seed-restart", ".db")
        try {
            val cfg = config(dbFile.absolutePath)
            Db.init(cfg)

            // Admin removes two permissions from the built-in 'user' role. POOL_GLOBAL_USE also
            // exercises the migrateGlobalPoolPermission path (the role keeps PROXY_USE).
            transaction {
                val rid = Roles.selectAll().where { Roles.name eq "user" }.first()[Roles.id]
                RolePermissions.deleteWhere {
                    (RolePermissions.roleId eq rid) and (RolePermissions.permission eq Permission.ROUTING_USE.name)
                }
                RolePermissions.deleteWhere {
                    (RolePermissions.roleId eq rid) and (RolePermissions.permission eq Permission.POOL_GLOBAL_USE.name)
                }
            }

            Db.init(cfg) // simulate a redeploy: service restarts against the same database

            val perms = userRolePerms()
            assertFalse(Permission.ROUTING_USE.name in perms, "removed ROUTING_USE came back after restart")
            assertFalse(Permission.POOL_GLOBAL_USE.name in perms, "removed POOL_GLOBAL_USE came back after restart")
            assertTrue(Permission.PROXY_USE.name in perms) // untouched permissions survive
        } finally {
            dbFile.delete()
        }
    }
}
