package com.chaoxing.mcpserver.db;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 只读 SQL 安全防护（工具层职责，先于数据库执行）。
 * <p>
 * 规则：
 * <ol>
 *   <li>SQL 必须为单条语句（禁止分号拼接多语句）；</li>
 *   <li>首关键字必须是 {@code SELECT} 或 {@code WITH}（CTE 只读）；</li>
 *   <li>禁止出现写/DDL 关键字：{@code INSERT / UPDATE / DELETE / DROP / ALTER /
 *       TRUNCATE / CREATE / GRANT / REVOKE / RENAME / CALL / LOAD / REPLACE /
 *       MERGE}，防止 CTE 体内夹带写操作；</li>
 *   <li>禁止注释符（{@code --}、{@code /*}）防 SQL 注入变形。</li>
 * </ol>
 * 校验失败抛 {@link UnsafeSqlException}，由工具方法捕获后返回结构化错误，
 * 不执行到数据库。
 * </p>
 */
public final class ReadonlySqlGuard {

    /** 写/DDL 危险关键字（在只读语句中作为独立词出现即拒绝）。 */
    private static final Pattern DANGEROUS_KEYWORDS = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|RENAME|CALL|LOAD|REPLACE|MERGE)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 注释符（含块注释与行注释）。 */
    private static final Pattern COMMENTS = Pattern.compile("--|/\\*|\\*/|//");

    private ReadonlySqlGuard() {
    }

    /**
     * 校验 SQL 是否为安全的只读单条语句。
     *
     * @param sql 原始 SQL
     * @return 归一化后的 SQL（去首尾空白与尾部分号）
     * @throws UnsafeSqlException 非只读 / 多语句 / 含危险结构时
     */
    public static String validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new UnsafeSqlException("empty SQL");
        }
        // 去尾部多余分号与空白
        String normalized = sql.trim();
        // 多条语句（分号后仍有实质内容）直接拒绝
        int lastSemi = normalized.lastIndexOf(';');
        if (lastSemi >= 0) {
            if (lastSemi < normalized.length() - 1 && !normalized.substring(lastSemi + 1).isBlank()) {
                throw new UnsafeSqlException("multiple statements are not allowed");
            }
            normalized = normalized.substring(0, lastSemi).trim();
        }

        if (COMMENTS.matcher(normalized).find()) {
            throw new UnsafeSqlException("SQL comments are not allowed");
        }

        String upper = normalized.toUpperCase(Locale.ROOT);
        if (!(upper.startsWith("SELECT") || upper.startsWith("WITH"))) {
            String head = upper.substring(0, Math.min(24, upper.length()));
            throw new UnsafeSqlException(
                    "readonly tool only allows SELECT or WITH (CTE) statements, got: " + head);
        }

        // CTE 体内或任何位置出现写/DDL 关键字即拒绝
        var m = DANGEROUS_KEYWORDS.matcher(normalized);
        if (m.find()) {
            throw new UnsafeSqlException("statement contains forbidden keyword: " + m.group(1).toUpperCase(Locale.ROOT));
        }

        return normalized;
    }
}
