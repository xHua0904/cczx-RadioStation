package com.cczxcsc.dao

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class AccountRecord(val username: String, val passwordHash: String, val encryptedPassword: String, val initialPassword: Boolean)
data class SessionRecord(val tokenHash: String, val username: String, val expiresAt: String)

@Repository
class AccountDao(private val jdbc: JdbcTemplate) {
    fun find(username: String): AccountRecord? = jdbc.query(
        "SELECT username,password_hash,password_encrypted,initial_password FROM accounts WHERE username=?",
        { row, _ -> AccountRecord(row.getString("username"), row.getString("password_hash"), row.getString("password_encrypted"), row.getInt("initial_password") == 1) }, username
    ).firstOrNull()

    fun insert(username: String, hash: String, encrypted: String, createdAt: String): Boolean = jdbc.update(
        "INSERT INTO accounts(username,password_hash,password_encrypted,initial_password,created_at) VALUES(?,?,?,1,?) ON CONFLICT(username) DO NOTHING",
        username, hash, encrypted, createdAt
    ) == 1

    fun list(): List<String> = jdbc.queryForList("SELECT username FROM accounts ORDER BY created_at,username", String::class.java)
    fun delete(username: String) { jdbc.update("DELETE FROM accounts WHERE username=?", username) }
    fun resetPassword(username: String, hash: String, encrypted: String): Boolean = jdbc.update(
        "UPDATE accounts SET password_hash=?,password_encrypted=?,initial_password=1 WHERE username=?", hash, encrypted, username
    ) == 1
    fun changePassword(username: String, expectedHash: String, hash: String, encrypted: String): Boolean = jdbc.update(
        "UPDATE accounts SET password_hash=?,password_encrypted=?,initial_password=0 WHERE username=? AND password_hash=?", hash, encrypted, username, expectedHash
    ) == 1

    fun deleteSessions(username: String) { jdbc.update("DELETE FROM sessions WHERE username=?", username) }
    fun deleteExpiredSessions(before: String) { jdbc.update("DELETE FROM sessions WHERE expires_at<?", before) }
    fun createSession(tokenHash: String, createdAt: String, expiresAt: String, username: String, expectedHash: String): Boolean = jdbc.update(
        "INSERT INTO sessions(token,created_at,expires_at,username) SELECT ?,?,?,username FROM accounts WHERE username=? AND password_hash=?",
        tokenHash, createdAt, expiresAt, username, expectedHash
    ) == 1
    fun session(tokenHash: String): SessionRecord? = jdbc.query(
        "SELECT s.token,s.username,s.expires_at FROM sessions s JOIN accounts a ON a.username=s.username WHERE s.token=?",
        { row, _ -> SessionRecord(row.getString("token"), row.getString("username"), row.getString("expires_at")) }, tokenHash
    ).firstOrNull()
    fun deleteSession(tokenHash: String) { jdbc.update("DELETE FROM sessions WHERE token=?", tokenHash) }
}
