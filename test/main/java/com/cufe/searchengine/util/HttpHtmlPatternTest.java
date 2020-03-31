package com.cufe.searchengine.util;

import org.junit.Test;

import static org.junit.jupiter.api.Assertions.*;

public class HttpHtmlPatternTest {
	@Test
	public void extractHtmlTitle() {
		String html = "<!doctype html>\n" +
			"<html lang=\"en-US\">\n" +
			"<head>\n" +
			"\t<meta charset=\"UTF-8\">\n" +
			"\t<meta name=viewport content=\"width=device-width, initial-scale=1\">\n" +
			"\t<title>What is Search Engine Indexing &amp; How Does it Work? - DeepCrawl</title>\n" +
			"\n" +
			"<!-- This site is optimized with the Yoast SEO plugin v11.9 - https://yoast.com/wordpress/plugins/seo/ -->\n" +
			"<meta name=\"description\" content=\"Let’s take a look at the indexing process that search engines use to store information about web pages, enabling them to quickly return relevant, high quality results.\"/>";

		String nonTitle = "<!doctype html>\n" +
			"<html lang=\"en-US\">\n" +
			"<head>\n" +
			"\t<meta charset=\"UTF-8\">\n" +
			"\t<meta name=viewport content=\"width=device-width, initial-scale=1\">\n" +
			"\tWhat is Search Engine Indexing &amp; How Does it Work? - DeepCrawl\n" +
			"\n" +
			"<!-- This site is optimized with the Yoast SEO plugin v11.9 - https://yoast.com/wordpress/plugins/seo/ -->\n" +
			"<meta name=\"description\" content=\"Let’s take a look at the indexing process that search engines use to store information about web pages, enabling them to quickly return relevant, high quality results.\"/>";

		assertEquals("What is Search Engine Indexing &amp; How Does it Work? - DeepCrawl", HttpHtmlPattern.extractHtmlTitle(html));
		assertEquals("", HttpHtmlPattern.extractHtmlTitle(nonTitle));
	}

	@Test
	public void extractWebsite() {
		final String expected = "https://wikipedia.org";
		final String[] inputs = new String[]{
			"https://wikipedia.org/asdfa/dafsd/a?q=aa%A",
			"https://www.wikipedia.org/asdfa/dafsd/lll?q=aa%A",
			"https://ar.wikipedia.org/asdfa/dafsd",
			"https://en.wikipedia.org/fadfb/ads/"
		};

		for (String input : inputs) {
			assertEquals(expected, HttpHtmlPattern.extractWebsite(input));
		}
	}

	@Test
	public void extractURLs() {
		String full = "https://www.google.com\n" +
			"http://www.google.com\n" +
			"www.google.com\n" +
			"htt://www.google.com\n" +
			"://www.google.com\n" +
			"http://wikipedia.org";

		assertArrayEquals(
			new String[]{"https://www.google.com", "http://www.google.com", "http://wikipedia.org"},
			HttpHtmlPattern.extractURLs(full)
		);
	}

	@Test
	public void couldBeHtml() {
		assertTrue(HttpHtmlPattern.couldBeHtml("adasd.html"));
		assertTrue(HttpHtmlPattern.couldBeHtml("adasd.asp"));
		assertTrue(HttpHtmlPattern.couldBeHtml("adasd"));
		assertTrue(HttpHtmlPattern.couldBeHtml(""));
		assertTrue(HttpHtmlPattern.couldBeHtml("adasd.$$$"));
		assertTrue(HttpHtmlPattern.couldBeHtml("*"));

		assertFalse(HttpHtmlPattern.couldBeHtml("adasd.jpg"));
		assertFalse(HttpHtmlPattern.couldBeHtml(".gif"));
		assertFalse(HttpHtmlPattern.couldBeHtml("adasd.habal"));
		assertFalse(HttpHtmlPattern.couldBeHtml("adasd.mp4"));
		assertFalse(HttpHtmlPattern.couldBeHtml("www.google.com/dasd/adasd.css"));
	}
}