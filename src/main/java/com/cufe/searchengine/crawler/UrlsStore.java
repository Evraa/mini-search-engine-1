package com.cufe.searchengine.crawler;

import com.cufe.searchengine.db.DBInitializer;
import com.cufe.searchengine.db.table.DocumentsTable;
import com.cufe.searchengine.db.table.UrlStoreQueueTable;
import com.cufe.searchengine.util.Patterns;
import com.cufe.searchengine.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.stream.Collectors;

@Component
public class UrlsStore {
	private static final Logger log = LoggerFactory.getLogger(UrlsStore.class);
	private static final Map<String, Long> websiteLastTime = new HashMap<>();
	private final BlockingQueue<ComparableUrl> store = new PriorityBlockingQueue<>();
	private final Set<String> allUrls = new HashSet<>();
	@Value("${crawler.seedFile}")
	private Resource crawlerSeedResource;
	@Autowired
	private RobotsStore robotsStore;
	@Autowired
	private UrlStoreQueueTable urlStoreQueueTable;
	private int counter = 1;

	@Autowired
	private DocumentsTable documentsTable;

	@EventListener
	public void onDBInitialized(DBInitializer.DBInitializedEvent event) throws Exception {
		List<String> urls = urlStoreQueueTable.selectUrls();

		if (urls.size() > 0) {
			store.addAll(
				urls.stream()
					.map(ComparableUrl::new)
					.collect(Collectors.toList())
			);

			log.info("urls loaded from db.size() = " + store.size());
		} else {
			List<ComparableUrl> list = StringUtils.resourceToLines(crawlerSeedResource)
				.stream()
				.map(ComparableUrl::new)
				.collect(Collectors.toList());

			store.addAll(
				list
			);

			log.info("loaded seeds of size=" + list.size());
		}

		readCounter();
		loadAllUrls();
		fillWebsiteLastTime();
	}

	private void fillWebsiteLastTime() throws Exception {
		websiteLastTime.clear();

		documentsTable.selectUrlTime(this::mapRowWebSiteLastTime);

		log.info("loaded into websiteLastTime {} items", websiteLastTime.size());
	}

	private void readCounter() throws Exception {
		counter = documentsTable.getPragmaUserVersion();
		log.info("loaded counter = {}", counter);
	}

	@EventListener
	public synchronized void onCrawlingFinished(DocumentsStore.CrawlingFinishedEvent event) throws Exception {
		log.info("crawling finished");

		incrementCounter();
		resetStore();
		loadAllUrls();
	}

	@Async
	@EventListener
	public synchronized void onDocumentStoredEvent(DocumentsStore.DocumentStoredEvent event) {
		long time = event.getTimeMillis();
		String website = Patterns.httpToHttps(Patterns.extractWebsite(event.getUrl()));

		websiteLastTime.computeIfPresent(website, (s, time0) -> Math.max(time0, time));
		websiteLastTime.putIfAbsent(website, time);
	}

	private void loadAllUrls() throws Exception {
		allUrls.clear();
		log.info("cleared allUrls");

		allUrls.addAll(queryAllUrlsToAvoid());
		log.info("fetched all urls, size={}", allUrls.size());
	}

	private void resetStore() throws Exception {
		store.clear();
		log.info("cleared store");

		store.addAll(
			queryAllUrlsToReuse().stream()
				.map(ComparableUrl::new)
				.collect(Collectors.toList())
		);
		log.info("added {} urls to store", store.size());
	}

	private void incrementCounter() throws Exception {
		counter++;
		documentsTable.setPragmaUserVersion(counter);
		log.info("incremented counter to {}", counter);
	}

	private List<String> queryAllUrlsToReuse() throws Exception {
		return documentsTable.selectUrlsNotThisCounter(counter);
	}

	private List<String> queryAllUrlsToAvoid() throws Exception {
		return documentsTable.selectUrlsThisCounter(counter);
	}

	@PreDestroy
	private void onDestroy() {
		String[] storeCopy = store.stream()
			.distinct()
			.filter(Objects::nonNull)
			.map(ComparableUrl::getUrl)
			.toArray(String[]::new);

		// flush
		try {
			urlStoreQueueTable.clean();
		} catch (Exception e) {
			log.error("failed to flush urlStoreQueueTable");
			e.printStackTrace();
			return;
		}

		log.info("flushed urlstore_queue table");

		// commit
		if (storeCopy.length == 0) {
			log.info("empty state, no saving");
			return;
		}
		log.info("save my state before closing");

		try {
			urlStoreQueueTable.insert(storeCopy);
		} catch (Exception e) {
			log.error("failed to save my state before closing");
			e.printStackTrace();
			return;
		}

		log.info("saved {} urls", storeCopy.length);
	}

	public void add(String url) throws InterruptedException {
		if (url == null) {
			return;
		}

		ComparableUrl comparableUrl = new ComparableUrl(url);

		// no duplicates
		if (store.contains(comparableUrl) || allUrls.contains(url)) {
			log.debug("duplicate url {}", url);
			return;
		}

		if (!robotsStore.canRequest(url, () -> store.remove(comparableUrl))) {
			return;
		}

		store.put(comparableUrl);
	}

	public String poll() throws InterruptedException {
		String url = store.take().getUrl();
		allUrls.add(url);
		return url;
	}

	public int size() {
		return store.size();
	}

	public int getCounter() {
		return counter;
	}

	private Void mapRowWebSiteLastTime(ResultSet r, int i) throws SQLException {
		long time = r.getLong(2);
		String website = Patterns.httpToHttps(Patterns.extractWebsite(r.getString(1)));

		websiteLastTime.computeIfPresent(website, (s, time0) -> Math.max(time0, time));
		websiteLastTime.putIfAbsent(website, time);

		return null;
	}

	private static class ComparableUrl implements Comparable<ComparableUrl> {
		private final String url;
		private final String normalizedUrl;

		private ComparableUrl(String url) {
			if (url == null) {
				throw new IllegalArgumentException("null url");
			}

			this.url = url;
			this.normalizedUrl = Patterns.httpToHttps(Patterns.extractWebsite(url));
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == null) {
				return false;
			}

			if (obj instanceof ComparableUrl) {
				ComparableUrl comparableUrl = (ComparableUrl) obj;
				return url.equals(comparableUrl.url);
			}

			return false;
		}

		public String getUrl() {
			return url;
		}

		@Override
		public int compareTo(ComparableUrl url2) {
			if (url2 == null) {
				return 1;
			}

			if (this.equals(url2)) {
				return 0;
			}

			return toInt(-(getTime() - url2.getTime()));
		}

		private int toInt(long l) {
			if (l > 0) {
				return 1;
			} else if (l < 0) {
				return -1;
			}
			
			return 0;
		}

		@Override
		public String toString() {
			return url;
		}

		private Long getTime() {
			return Optional
				.ofNullable(websiteLastTime.putIfAbsent(normalizedUrl, System.currentTimeMillis()))
				.orElse(System.currentTimeMillis());
		}
	}
}
