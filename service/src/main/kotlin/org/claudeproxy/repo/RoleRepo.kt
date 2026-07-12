package org.claudeproxy.repo

import kotlinx.serialization.Serializable
import org.claudeproxy.db.RolePermissions
import org.claudeproxy.db.Roles
import org.claudeproxy.model.Permission
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class RoleDto(val id: Int, val name: String, val permissions: List<String>)

object RoleRepo {

    fun list(): List<RoleDto> = transaction {
        Roles.selectAll().map { row ->
            val rid = row[Roles.id]
            val perms = RolePermissions.selectAll().where { RolePermissions.roleId eq rid }
                .map { it[RolePermissions.permission] }
            RoleDto(rid, row[Roles.name], perms)
        }
    }

    fun allPermissions(): List<String> = Permission.entries.map { it.name }

    fun create(name: String, permissions: List<String>): Int = transaction {
        val rid = Roles.insert { it[Roles.name] = name }[Roles.id]
        setPermissions(rid, permissions)
        rid
    }

    fun setPermissions(roleId: Int, permissions: List<String>) = transaction {
        RolePermissions.deleteWhere { RolePermissions.roleId eq roleId }
        permissions.mapNotNull { Permission.fromString(it) }.forEach { p ->
            RolePermissions.insertIgnore {
                it[RolePermissions.roleId] = roleId
                it[permission] = p.name
            }
        }
    }

    fun delete(roleId: Int): Boolean = transaction {
        org.claudeproxy.db.UserRoles.deleteWhere { org.claudeproxy.db.UserRoles.roleId eq roleId }
        RolePermissions.deleteWhere { RolePermissions.roleId eq roleId }
        Roles.deleteWhere { Roles.id eq roleId } > 0
    }
}
