package org.claudeproxy.repo

import org.claudeproxy.auth.Passwords
import org.claudeproxy.db.RolePermissions
import org.claudeproxy.db.Roles
import org.claudeproxy.db.UserGroupAccess
import org.claudeproxy.db.UserRoles
import org.claudeproxy.db.Users
import org.claudeproxy.model.Permission
import org.claudeproxy.model.UserDto
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

data class UserAuth(val id: Int, val username: String, val enabled: Boolean, val permissions: Set<Permission>)

object UserRepo {

    fun authenticate(username: String, password: String): UserAuth? = transaction {
        val row = Users.selectAll().where { Users.username eq username }.firstOrNull() ?: return@transaction null
        if (!row[Users.enabled]) return@transaction null
        if (!Passwords.verify(password, row[Users.passwordHash])) return@transaction null
        toAuth(row)
    }

    fun findAuth(userId: Int): UserAuth? = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction null
        toAuth(row)
    }

    private fun toAuth(row: ResultRow): UserAuth {
        val uid = row[Users.id]
        return UserAuth(uid, row[Users.username], row[Users.enabled], permissionsOf(uid))
    }

    fun permissionsOf(userId: Int): Set<Permission> = transaction {
        val roleIds = UserRoles.selectAll().where { UserRoles.userId eq userId }.map { it[UserRoles.roleId] }
        if (roleIds.isEmpty()) return@transaction emptySet()
        val perms = RolePermissions.selectAll().where { RolePermissions.roleId inList roleIds }
            .mapNotNull { Permission.fromString(it[RolePermissions.permission]) }
            .toMutableSet()
        if (Permission.ADMIN in perms) return@transaction Permission.entries.toSet()
        perms
    }

    fun rolesOf(userId: Int): List<String> = transaction {
        val roleIds = UserRoles.selectAll().where { UserRoles.userId eq userId }.map { it[UserRoles.roleId] }
        if (roleIds.isEmpty()) return@transaction emptyList()
        Roles.selectAll().where { Roles.id inList roleIds }.map { it[Roles.name] }
    }

    fun list(): List<UserDto> = transaction {
        Users.selectAll().map { row ->
            val uid = row[Users.id]
            toDto(uid, row[Users.username], row[Users.enabled])
        }
    }

    fun get(userId: Int): UserDto? = transaction {
        val row = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return@transaction null
        toDto(row[Users.id], row[Users.username], row[Users.enabled])
    }

    private fun toDto(uid: Int, username: String, enabled: Boolean): UserDto {
        val perms = permissionsOf(uid)
        return UserDto(
            id = uid,
            username = username,
            enabled = enabled,
            roles = rolesOf(uid),
            permissions = perms.map { it.name },
            allowedGroups = allowedGroupsOf(uid).toList(),
            allGroups = Permission.ADMIN in perms,
        )
    }

    fun allowedGroupsOf(userId: Int): Set<Int> = transaction {
        UserGroupAccess.selectAll().where { UserGroupAccess.userId eq userId }
            .map { it[UserGroupAccess.groupId] }.toSet()
    }

    fun setAllowedGroups(userId: Int, groupIds: List<Int>) = transaction {
        UserGroupAccess.deleteWhere { UserGroupAccess.userId eq userId }
        groupIds.distinct().forEach { gid ->
            UserGroupAccess.insert {
                it[UserGroupAccess.userId] = userId
                it[groupId] = gid
            }
        }
    }

    fun create(username: String, password: String, roleNames: List<String>, groupIds: List<Int>): Int = transaction {
        val uid = Users.insert {
            it[Users.username] = username
            it[passwordHash] = Passwords.hash(password)
            it[enabled] = true
            it[createdAt] = Instant.now()
        }[Users.id]
        setRoles(uid, roleNames)
        setAllowedGroups(uid, groupIds)
        uid
    }

    fun update(userId: Int, password: String?, enabled: Boolean?, roleNames: List<String>?, groupIds: List<Int>?) = transaction {
        if (password != null || enabled != null) {
            Users.update({ Users.id eq userId }) {
                if (password != null) it[passwordHash] = Passwords.hash(password)
                if (enabled != null) it[Users.enabled] = enabled
            }
        }
        if (roleNames != null) setRoles(userId, roleNames)
        if (groupIds != null) setAllowedGroups(userId, groupIds)
    }

    fun delete(userId: Int) = transaction {
        UserGroupAccess.deleteWhere { UserGroupAccess.userId eq userId }
        UserRoles.deleteWhere { UserRoles.userId eq userId }
        Users.deleteWhere { Users.id eq userId }
    }

    private fun setRoles(userId: Int, roleNames: List<String>) {
        UserRoles.deleteWhere { UserRoles.userId eq userId }
        roleNames.forEach { rn ->
            val rid = Roles.selectAll().where { Roles.name eq rn }.firstOrNull()?.get(Roles.id) ?: return@forEach
            UserRoles.insert {
                it[UserRoles.userId] = userId
                it[roleId] = rid
            }
        }
    }
}
