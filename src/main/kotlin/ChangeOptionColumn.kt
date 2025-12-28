package org.example

import java.sql.DriverManager

val rootFolderPath = "/Users/quan/Downloads/datgamedetail"
val outputDbPath = rootFolderPath + "/master_metadata.db"


  val DB_PATH = outputDbPath
fun main() {
    Class.forName("org.sqlite.JDBC")

    val url = "jdbc:sqlite:$DB_PATH"
    DriverManager.getConnection(url).use { conn ->
        conn.autoCommit = false

        try {
            conn.createStatement().use { stmt ->

                // 1. Tạo bảng mới NOT NULL
                stmt.execute("""
                    CREATE TABLE game_detail_new (
                        crc TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL DEFAULT '',
                        md5 TEXT NOT NULL DEFAULT '',
                        sha1 TEXT NOT NULL DEFAULT '',
                        developer TEXT NOT NULL DEFAULT '',
                        publisher TEXT NOT NULL DEFAULT '',
                        genre TEXT NOT NULL DEFAULT '',
                        releaseYear INTEGER NOT NULL DEFAULT 0,
                        releaseMonth INTEGER NOT NULL DEFAULT 0,
                        platform TEXT NOT NULL DEFAULT '',
                        coverArtPath TEXT NOT NULL DEFAULT ''
                    )
                """)

                // 2. Copy dữ liệu + xử lý NULL
                stmt.execute("""
                    INSERT INTO game_detail_new
                    SELECT
                        crc,
                        IFNULL(title, ''),
                        IFNULL(md5, ''),
                        IFNULL(sha1, ''),
                        IFNULL(developer, ''),
                        IFNULL(publisher, ''),
                        IFNULL(genre, ''),
                        IFNULL(releaseYear, 0),
                        IFNULL(releaseMonth, 0),
                        IFNULL(platform, ''),
                        IFNULL(coverArtPath, '')
                    FROM game_detail
                """)

                // 3. Xóa bảng cũ
                stmt.execute("DROP TABLE game_detail")

                // 4. Đổi tên bảng mới
                stmt.execute("ALTER TABLE game_detail_new RENAME TO game_detail")

                // 5. Tạo lại index
                stmt.execute("CREATE INDEX index_game_detail_platform ON game_detail(platform)")
                stmt.execute("CREATE INDEX index_game_detail_title ON game_detail(title)")
            }

            conn.commit()
            println("✅ Migration to NOT NULL completed successfully!")

        } catch (e: Exception) {
            conn.rollback()
            println("❌ Migration failed, rolled back")
            e.printStackTrace()
        }
    }
}
