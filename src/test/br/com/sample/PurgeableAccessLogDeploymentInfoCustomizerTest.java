package br.com.sample;

import static java.io.File.separator;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.boot.autoconfigure.web.ServerProperties.Undertow.Accesslog;

import br.com.sample.AccessLogSampleApplication.PurgeProperties;
import br.com.sample.AccessLogSampleApplication.PurgeableAccessLogDeploymentInfoCustomizer;

@RunWith(MockitoJUnitRunner.class)
public class PurgeableAccessLogDeploymentInfoCustomizerTest {

	private static final String TEMP_DIR = "/tmp/access_log_test";
	private static final String FILE_NAME_PATTERN = "%s/%s%s-0%d.%s";
	private static final int FILES_AMOUNT = 3;
	private PurgeableAccessLogDeploymentInfoCustomizer customizerUnderTest;
	private PurgeProperties purgeProperties;
	private Accesslog accesslog;

	@Before
	public void setUp() throws Exception {
		this.resetTestDir();

		this.purgeProperties = new PurgeProperties();
		this.purgeProperties.setEnabled(true);
		this.purgeProperties.setExecutionInterval(2);
		this.purgeProperties.setExecutionIntervalUnit(SECONDS);
		this.purgeProperties.setMaxHistory(5);
		this.purgeProperties.setMaxHistoryUnit(SECONDS);

		this.accesslog = new Accesslog();
		this.accesslog.setDir(new File(TEMP_DIR));

		this.customizerUnderTest = new PurgeableAccessLogDeploymentInfoCustomizer(this.purgeProperties, this.accesslog);
		new File(TEMP_DIR + separator + this.accesslog.getPrefix() + this.accesslog.getSuffix()).createNewFile();

		this.createOldFiles();
	}

	@Test
	public void testCustomize() throws Exception {
		this.customizerUnderTest.customize(null);
		final String[] fileNames = this.getFileNamesFromAccessLogDir();
		assertNotNull(fileNames);
		assertEquals(FILES_AMOUNT + 1, fileNames.length);
	}

	@Test
	public void testCustomizeExecutingOnStartup() throws Exception {
		this.purgeProperties.setExecuteOnStartup(true);
		this.customizerUnderTest.customize(null);

		String[] fileNames = this.getFileNamesFromAccessLogDir();
		assertNotNull(fileNames);
		assertEquals(FILES_AMOUNT + 1, fileNames.length);

		SECONDS.sleep(10);
		fileNames = this.getFileNamesFromAccessLogDir();
		assertNotNull(fileNames);
		assertEquals(1, fileNames.length);

		this.createOldFiles();

		SECONDS.sleep(10);
		fileNames = this.getFileNamesFromAccessLogDir();
		assertNotNull(fileNames);
		assertEquals(1, fileNames.length);
	}

	private void resetTestDir() {
		final File testDir = new File(TEMP_DIR);
		if (!testDir.exists()) {
			testDir.mkdir();
		} else {
			final File[] files = testDir.listFiles();
			if (files != null) {
				for (final File file : files) {
					file.delete();
				}
			}
		}
	}

	private void createOldFiles() throws IOException {
		final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM");
		for (int i = 0; i < FILES_AMOUNT; i++) {
			final String fileName = format(FILE_NAME_PATTERN, this.accesslog.getDir(), this.accesslog.getPrefix(), format.format(new Date()), i + 1, this.accesslog.getSuffix());
			new File(fileName).createNewFile();
		}
	}

	private String[] getFileNamesFromAccessLogDir() {
		return this.accesslog.getDir().list();
	}
}