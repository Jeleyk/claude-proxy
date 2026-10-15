package org.claudeproxy.auth

import org.claudeproxy.auth.OidcLoginResult.Denied
import org.claudeproxy.auth.OidcLoginResult.How
import org.claudeproxy.auth.OidcLoginResult.Success
import org.claudeproxy.cache.MemoryCache
import org.claudeproxy.model.Permission
import org.claudeproxy.repo.IdentityRepo
import org.claudeproxy.repo.UserRepo
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.*

/** Linking rules, JIT provisioning and the claim gates — the policy behind a *validated* ID token. */
class OidcLoginTest {
    private lateinit var dbFile: File
    private val iss = "https://idp.example.com"
    private var alice = 0

    @BeforeTest
    fun setup() {
        dbFile = freshDb()
        alice = UserRepo.create("alice", "pw-alice", listOf("user"), emptyList(), null, email = "alice@example.com")
    }

    @AfterTest
    fun cleanup() { MemoryCache.clear(); dbFile.delete() }

    private fun id(
        sub: String = "sub-alice", email: String? = "alice@example.com", verified: Boolean = true,
        groups: List<String>? = listOf("panel"), username: String? = null,
    ) = OidcIdentity(iss, sub, email, verified, groups, username)

    private fun login(cfg: OidcConfig, identity: OidcIdentity) = OidcLogin(cfg).resolve(identity)
    private fun denied(r: OidcLoginResult) = assertIs<Denied>(r).error
    private fun linkedTo(sub: String) = transaction { IdentityRepo.userIdFor(iss, sub) }

    @Test
    fun `no linking by email unless enabled`() {
        assertEquals(OidcError.NO_ACCOUNT, denied(login(oidcConfig(), id())))
        assertNull(linkedTo("sub-alice"))
    }

    @Test
    fun `verified email links an unlinked user once, then sub alone matches`() {
        val first = assertIs<Success>(login(oidcConfig(linkByEmail = true), id()))
        assertEquals(alice to How.LINKED_BY_EMAIL, first.user.id to first.how)
        // Later logins match on (iss, sub) even if the email claim changed.
        val again = assertIs<Success>(login(oidcConfig(linkByEmail = true), id(email = "renamed@example.com")))
        assertEquals(alice to How.LINKED, again.user.id to again.how)
    }

    @Test
    fun `unverified email never links`() {
        assertEquals(OidcError.NO_ACCOUNT, denied(login(oidcConfig(linkByEmail = true), id(verified = false))))
        assertNull(linkedTo("sub-alice"))
    }

    @Test
    fun `a user already linked to another subject is never re-pointed`() {
        transaction { IdentityRepo.link(alice, iss, "sub-original") }
        val r = assertIs<Denied>(login(oidcConfig(linkByEmail = true, jit = true, jitRole = "user"), id(sub = "sub-attacker")))
        assertEquals(OidcError.NO_ACCOUNT, r.error)
        // Refused by the explicit check, not merely by the unique index catching the INSERT.
        assertTrue("already linked" in r.reason, r.reason)
        assertEquals(alice, linkedTo("sub-original"))
        assertNull(linkedTo("sub-attacker"))
    }

    @Test
    fun `disabled users are refused whether linked or email-matched`() {
        UserRepo.update(alice, null, false, null, null, null, false)
        assertEquals(OidcError.FORBIDDEN, denied(login(oidcConfig(linkByEmail = true), id())))
        assertNull(linkedTo("sub-alice"))
        transaction { IdentityRepo.link(alice, iss, "sub-alice") }
        assertEquals(OidcError.FORBIDDEN, denied(login(oidcConfig(), id())))
    }

    @Test
    fun `username is never used for matching`() {
        // IdP user claims the admin's username; without JIT that is simply no account.
        assertEquals(OidcError.NO_ACCOUNT, denied(login(oidcConfig(), id(sub = "x", email = null, username = "admin"))))
        // With JIT they get a fresh account next to it, never the admin.
        val r = assertIs<Success>(login(oidcConfig(jit = true, jitRole = "user"), id(sub = "x", email = null, username = "admin")))
        assertNotEquals(1, r.user.id)
        assertEquals("admin-2", r.user.username)
    }

    @Test
    fun `JIT default creates a disabled, role-less pending account that can do nothing`() {
        val r = assertIs<Denied>(login(oidcConfig(jit = true), id(sub = "new", email = "bob@example.com", username = "bob")))
        assertEquals(OidcError.PENDING, r.error)
        val uid = transaction { IdentityRepo.userIdFor(iss, "new") }!!
        val dto = UserRepo.get(uid)!!
        assertFalse(dto.enabled)
        assertTrue(dto.roles.isEmpty() && dto.permissions.isEmpty() && dto.allowedGroups.isEmpty())
        assertTrue(UserRepo.permissionsOf(uid).isEmpty())
        // Logging in again before approval still says "pending"; an admin enabling + assigning a role lets them in.
        assertEquals(OidcError.PENDING, denied(login(oidcConfig(jit = true), id(sub = "new", email = "bob@example.com"))))
        assertEquals(OidcError.PENDING, denied(login(oidcConfig(), id(sub = "new", email = "bob@example.com"))), "also once JIT is switched off")
        UserRepo.update(uid, null, true, listOf("user"), null, null, false)
        assertIs<Success>(login(oidcConfig(jit = true), id(sub = "new", email = "bob@example.com")))
        // Approved, then switched off (or offboarded): that is "forbidden", not "pending".
        UserRepo.update(uid, null, false, null, null, null, false)
        assertEquals(OidcError.FORBIDDEN, denied(login(oidcConfig(jit = true), id(sub = "new", email = "bob@example.com"))))
        UserRepo.update(uid, null, true, null, null, null, false)
        UserRepo.offboard(uid)
        assertEquals(OidcError.FORBIDDEN, denied(login(oidcConfig(jit = true), id(sub = "new", email = "bob@example.com"))))
    }

    @Test
    fun `linking ends the user's existing panel sessions, the SSO session gets the new generation`() {
        val before = UserRepo.findAuth(alice)!!.sessionVersion
        val r = assertIs<Success>(login(oidcConfig(linkByEmail = true), id()))
        val after = UserRepo.findAuth(alice)!!.sessionVersion
        assertTrue(after > before, "pre-link (password) sessions are revoked")
        assertEquals(after, r.user.sessionVersion, "the session issued for this login is valid")
        // A returning login does not revoke anything.
        assertEquals(after, assertIs<Success>(login(oidcConfig(linkByEmail = true), id())).user.sessionVersion)
        assertEquals(after, UserRepo.findAuth(alice)!!.sessionVersion)
        // Any link (also an admin/test-side one) bumps the generation.
        val bob = UserRepo.create("bob", "pw", listOf("user"), emptyList(), null)
        val v = UserRepo.findAuth(bob)!!.sessionVersion
        transaction { IdentityRepo.link(bob, iss, "sub-bob") }
        assertEquals(v + 1, UserRepo.findAuth(bob)!!.sessionVersion)
    }

    @Test
    fun `JIT with an unknown role creates nothing`() {
        assertEquals(OidcError.NO_ACCOUNT, denied(login(oidcConfig(jit = true, jitRole = "nope"), id(sub = "n9", email = null, username = "zed"))))
        assertNull(linkedTo("n9"))
        assertFalse(UserRepo.list().any { it.username == "zed" })
    }

    @Test
    fun `JIT is off by default, with OIDC_JIT_ROLE the account gets exactly that role and no group access`() {
        assertEquals(OidcError.NO_ACCOUNT, denied(login(oidcConfig(), id(sub = "new", email = "bob@example.com", username = "bob"))))
        val r = assertIs<Success>(login(oidcConfig(jit = true, jitRole = "user"), id(sub = "new", email = "bob@example.com", username = "bob")))
        assertEquals(How.PROVISIONED, r.how)
        val dto = UserRepo.get(r.user.id)!!
        assertEquals(listOf("user"), dto.roles)
        assertTrue(dto.allowedGroups.isEmpty() && !dto.allGroups)
        assertFalse(Permission.ADMIN in r.user.permissions || Permission.USERS_MANAGE in r.user.permissions)
        assertEquals("bob@example.com", dto.email)
        assertTrue(dto.ssoLinked)
        // The random password is unknown to everyone.
        assertNull(UserRepo.authenticate("bob", ""))
    }

    @Test
    fun `JIT keeps only a verified, unused email and sanitizes the username`() {
        val r = assertIs<Success>(login(oidcConfig(jit = true, jitRole = "user"), id(sub = "n2", email = "alice@example.com", username = "Мallory <x>")))
        val dto = UserRepo.get(r.user.id)!!
        assertNull(dto.email, "alice's email stays alice's")
        assertEquals("alloryx", dto.username)
        val u = assertIs<Success>(login(oidcConfig(jit = true, jitRole = "user"), id(sub = "n3", email = "carol@example.com", verified = false, username = "carol")))
        assertNull(UserRepo.get(u.user.id)!!.email)
    }

    @Test
    fun `group gate applies to every login and needs the claim`() {
        transaction { IdentityRepo.link(alice, iss, "sub-alice") }
        val cfg = oidcConfig(allowedGroups = setOf("claude-proxy-user", "admin"))
        assertEquals(OidcError.FORBIDDEN, denied(login(cfg, id(groups = null))))
        assertEquals(OidcError.FORBIDDEN, denied(login(cfg, id(groups = emptyList()))))
        assertEquals(OidcError.FORBIDDEN, denied(login(cfg, id(groups = listOf("Claude-Proxy-User")))), "exact, case-sensitive")
        assertIs<Success>(login(cfg, id(groups = listOf("x", "claude-proxy-user"))))
    }

    @Test
    fun `email domain allowlist is exact and needs a verified email`() {
        transaction { IdentityRepo.link(alice, iss, "sub-alice") }
        val cfg = oidcConfig(allowedEmailDomains = setOf("example.com"))
        assertIs<Success>(login(cfg, id()))
        assertEquals(OidcError.FORBIDDEN, denied(login(cfg, id(verified = false))))
        assertEquals(OidcError.FORBIDDEN, denied(login(cfg, id(email = null))))
        assertEquals(OidcError.FORBIDDEN, denied(login(cfg, id(email = "a@evil-example.com"))))
        assertEquals(OidcError.FORBIDDEN, denied(login(cfg, id(email = "a@sub.example.com"))))
        assertEquals(OidcError.FORBIDDEN, denied(login(cfg, id(email = "a@example.com@evil.com"))))
    }

    @Test
    fun `deleting an SSO-linked user works, also the way the previous release deletes (FK cascade)`() {
        transaction { IdentityRepo.link(alice, iss, "sub-alice") }
        UserRepo.delete(alice)
        assertNull(linkedTo("sub-alice"))
        // Previous release: knows nothing about user_identities, deletes role rows then the user.
        val dave = UserRepo.create("dave", "pw", listOf("user"), emptyList(), null)
        transaction { IdentityRepo.link(dave, iss, "sub-dave") }
        transaction {
            exec("DELETE FROM user_roles WHERE user_id = $dave")
            exec("DELETE FROM users WHERE id = $dave")
        }
        assertNull(linkedTo("sub-dave"))
    }

    @Test
    fun `admin-set emails are normalized and unique`() {
        assertEquals("bob@example.com", UserRepo.normalizeEmail("  Bob@Example.COM "))
        assertNull(UserRepo.normalizeEmail("  "))
        assertFails { UserRepo.normalizeEmail("no-at-sign") }
        assertFails { UserRepo.normalizeEmail("a@b@c") }
        val bob = UserRepo.create("bob", "pw", listOf("user"), emptyList(), null)
        assertFailsWith<org.claudeproxy.repo.EmailTakenException> {
            UserRepo.update(bob, null, null, null, null, null, false, email = "alice@example.com")
        }
    }

    @Test
    fun `losing the check-then-write race on an email still reports EmailTaken, not a SQL error`() {
        val bob = UserRepo.create("bob", "pw", listOf("user"), emptyList(), null)
        UserRepo.emailPrecheck = false // the concurrent writer got in after the pre-check
        try {
            assertFailsWith<org.claudeproxy.repo.EmailTakenException> {
                UserRepo.update(bob, null, null, null, null, null, false, email = "alice@example.com")
            }
            assertFailsWith<org.claudeproxy.repo.EmailTakenException> {
                UserRepo.create("carol", "pw", listOf("user"), emptyList(), null, email = "alice@example.com")
            }
            // A constraint violation that is not about the email is not disguised as one.
            assertFailsWith<org.jetbrains.exposed.exceptions.ExposedSQLException> {
                UserRepo.create("bob", "pw", listOf("user"), emptyList(), null, email = "new@example.com")
            }
        } finally { UserRepo.emailPrecheck = true }
        assertNull(UserRepo.get(bob)!!.email)
        assertFalse(UserRepo.list().any { it.username == "carol" })
    }
}
