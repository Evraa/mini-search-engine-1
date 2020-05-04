package com.cufe.searchengine.db.table;

import com.cufe.searchengine.crawler.Document;
import com.cufe.searchengine.server.model.QueryResult;
import com.cufe.searchengine.util.DBUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Hashtable;

@Component
public class DocumentsTable {
	@Autowired
	private JdbcTemplate jdbcTemplate;

	public Integer size() throws Exception {
		return DBUtils.waitLock(100, () -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM documents;", Integer.class));
	}

	public void replace(String url, String content, long timeMillis, int counter) throws Exception {
		Integer rows = DBUtils.waitLock(100,
			() -> jdbcTemplate.update("REPLACE INTO documents(url, content, timeMillis, counter) " +
				"VALUES(?, ?, ?, ?);", url, content, timeMillis, counter)
		);

		if (rows == null || rows != 1) {
			throw new SQLException("couldn't replace into documents url=" + url);
		}
	}

	public void selectUrlTime(RowMapper<Void> mapper) throws Exception {
		DBUtils.waitLock(100,
			() -> jdbcTemplate.query(
				"SELECT url, timeMillis FROM documents;", mapper
			)
		);
	}

	public List<String> selectUrls() throws Exception {
		return DBUtils.waitLock(100,
			() -> jdbcTemplate.queryForList("SELECT url FROM urlstore_queue;", String.class)
		);
	}

	public int getPragmaUserVersion() throws Exception {
		return DBUtils.waitLock(100,
			() -> jdbcTemplate.queryForObject("PRAGMA user_version;", Integer.class));
	}

	public void setPragmaUserVersion(int counter) throws Exception {
		DBUtils.waitLock(100, () -> jdbcTemplate.update("PRAGMA user_version = ?;", counter));
	}

	public List<String> selectUrlsNotThisCounter(int counter) throws Exception {
		return DBUtils.waitLock(100, () -> jdbcTemplate.query("SELECT url FROM documents WHERE counter != ?;",
			(row, i) -> row.getString(1), counter));
	}

	public List<String> selectUrlsThisCounter(int counter) throws Exception {
		return DBUtils.waitLock(100, () -> jdbcTemplate.query("SELECT url FROM documents WHERE counter = ?;",
			(row, i) -> row.getString(1), counter));
	}

	public int updateIndexTime(List<Document> documents) throws Exception {
		StringBuilder builder = new StringBuilder("UPDATE documents SET indexTimeMillis = ? WHERE ROWID in (");
		for (int i = 0; i < documents.size() - 1; i++) {
			builder.append(documents.get(i).getRowID()).append(",");
		}
		builder.append(documents.get(documents.size() - 1).getRowID()).append(");");

		int rows = DBUtils.waitLock(100, () -> jdbcTemplate.update(builder.toString(), System.currentTimeMillis()));
		if (rows != documents.size()) {
			throw new RuntimeException("should have updated " + documents.size());
		}
		return rows;
	}

	public List<Document> selectAll(int maxDocumentsPerIteration) throws Exception {
		return DBUtils.waitLock(100, () -> jdbcTemplate.query(String.format("SELECT content, url, timeMillis, indexTimeMillis, ROWID FROM documents WHERE " +
				"indexTimeMillis < timeMillis LIMIT %d;", maxDocumentsPerIteration),
			(row, i) -> new Document(row.getString(1), row
				.getString(2), row.getLong(3)).indexTimeMillis(row.getLong(4))
				.rowID(row.getLong(5))));
	}

	public List<QueryResult> selectContentUrlLikePhrases(List<String> phrases) throws Exception {
		// TODO: sort with rank
		// TODO: limit by page
		Optional<String> reducedLikes = phrases.stream()
			.map(String::toLowerCase)
			.distinct()
			.map(p -> MessageFormat.format("content LIKE \"%{0}%\"", p))
			.reduce((s, s2) -> MessageFormat.format("{0} OR {1}", s, s2));

		if (!reducedLikes.isPresent()) {
			return new ArrayList<>();
		}

		String query = "SELECT content, url FROM documents WHERE " + reducedLikes.get() + ";";

		return DBUtils.waitLock(100, () -> jdbcTemplate.query(query, (row, i) -> new Document(row.getString(1),
			row.getString(2), 0))
			.stream()
			.map(d -> new QueryResult().title(d.getTitle())
				.snippet(d.getSnippet(phrases))
				.link(d.getUrl()))
			.collect(Collectors.toList()));
	}

	public List<Document> selectContentUrlSorted(List<String> keywords) throws Exception {
		// TODO: sort with rank
		// TODO: limit by page
		StringBuilder builder = new StringBuilder("SELECT content, url FROM documents d " + "INNER JOIN " +
			"keywords_documents kd ON d.ROWID = kd.docID " + "INNER JOIN " + "keywords k ON k.ROWID = kd.wordID AND k.word in (");
		for (int i = 0; i < keywords.size(); i++) {
			builder.append("?");
			if (i != keywords.size() - 1) {
				builder.append(",");
			}
		}
		builder.append(");");

		return DBUtils.waitLock(100, () -> jdbcTemplate.query(builder.toString(),
			(row, i) -> new Document(row.getString(1), row
				.getString(2), 0), keywords.toArray()));
	}

	public Integer selectURLRank(String url) throws Exception {
		String query = "SELECT rank FROM documents WHERE url = '" + url + "';";
		return DBUtils.waitLock(100, () -> jdbcTemplate.queryForObject(query, Integer.class));
	}

	public Hashtable<String, Integer> selectAllURLRanks() throws Exception {
		Hashtable<String, Integer> urlRanks = new Hashtable<String, Integer>();
		List<String> urls = selectUrls();
		for (String url : urls) {
			urlRanks.put(url, selectURLRank(url));
		}
		return urlRanks;
	}

	public void updateAllURLRanks(Hashtable<String, Integer> urlRanks) throws Exception {
		urlRanks.forEach((k, v) -> {
			try {
				DBUtils.waitLock(100, () -> jdbcTemplate.update("UPDATE documents SET rank = (?) WHERE url = (?);", v, k));
			} catch (Exception e) {
				throw new RuntimeException("URL rank update failed");
			}
		});
	}
}
