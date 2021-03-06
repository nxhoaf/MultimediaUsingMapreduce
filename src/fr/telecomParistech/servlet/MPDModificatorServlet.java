package fr.telecomParistech.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.IOUtils;

import com.google.appengine.api.files.AppEngineFile;
import com.google.appengine.api.files.FileService;
import com.google.appengine.api.files.FileServiceFactory;
import com.google.appengine.api.files.FileWriteChannel;

import fr.telecomParistech.dash.mpd.AdaptationSet;
import fr.telecomParistech.dash.mpd.InitSegment;
import fr.telecomParistech.dash.mpd.MPD;
import fr.telecomParistech.dash.mpd.MPDParser;
import fr.telecomParistech.dash.mpd.MediaSegment;
import fr.telecomParistech.dash.mpd.Period;
import fr.telecomParistech.dash.mpd.Representation;
import fr.telecomParistech.dash.mpd.SegmentList;

/**
 * When this servlet receives an URL pointing to a MPD file, it downloads the
 * file, read it content to find out media segments. Then, it uploads all media
 * files to GAE blobstore. Finally, it modify MPD file so that it points to new
 * locations of these media segments. 
 * @author xuan-hoa.nguyen@telecom-paristech.fr
 *
 */
public class MPDModificatorServlet extends HttpServlet {
	private static final long serialVersionUID = 2235198614628917491L;
	private static final String BLOBSTORE_READER_SERVICE = 
			"blobstore-reader-servlet";

	// Configuration-related properties
	private static final Logger log;
	private static final String CONFIG_FILE="WEB-INF/mapreduce-config.xml";
	private static final XMLConfiguration mapreduceConfig;
	// GEA services
	private static final FileService fileService = 
			FileServiceFactory.getFileService(); 

	// Counter, help us to keep track of each map task.
	// static initializer 
	static {
		log = Logger.getLogger(MPDModificatorServlet.class.getName());

		// First, set log level in order to display log info during this 
		// static initializer. It's also the default log level
		log.setLevel(Level.INFO);
		XMLConfiguration tmpConfig = null; 
		try {
			tmpConfig = new XMLConfiguration(CONFIG_FILE);
		} catch (Exception e) {
			log.severe("Couldn't read config file: " + CONFIG_FILE);
			System.exit(1);
		} finally {
			mapreduceConfig = tmpConfig;
			if (mapreduceConfig != null) {
				String logLevel = 
						mapreduceConfig.getString("log.level-mapper", "INFO");
				log.setLevel(Level.parse(logLevel));

			} 
		}
	}

	/**
	 * Get directory which contains mpd file from the fileUrl, for e.x, if
	 * the fileUrl is http://link/to/file.txt, the return will be "to" folder
	 * @param fileUrl fileUrl to get directory
	 * @return the folder containing the file
	 */
	private String getDirectoryUrl(String fileUrl) {
		int delimIndex = fileUrl.lastIndexOf('/');
		String dirUrl = fileUrl.substring(0, delimIndex);
		return dirUrl;
	}

	@Override protected void doPost(HttpServletRequest request, 
			HttpServletResponse resp)
					throws ServletException, IOException {

		// Log the start time
		long startedTime = System.nanoTime();
		log.info("MPDParser started at " + startedTime + " (ABSOLUTE TIME)");

		String senderUrl = request.getParameter("senderUrl");
		if (senderUrl == null) {
			throw new IllegalStateException("sender url is null");
		}
		URL url = new URL(senderUrl);
		
		// Store MPD to a String for later user
		StringBuilder stringBuilder = new StringBuilder();
		BufferedReader reader = 
				new BufferedReader(new InputStreamReader(url.openStream()));
		String line = null;
		while ((line = reader.readLine()) != null) {
			stringBuilder.append(line + "\n");
		}
		reader.close();
		String mpdTextFormat = stringBuilder.toString();
		
		// Read MPD file into MPD object
		MPD mpd = MPDParser.parseMPD(url);

		// Ok, now parse mpd object
		// ------- Parse it ------------
		List<Period> periods = mpd.getAllPeriod();

		// Get file and dirUrl
		String fileUrl = request.getParameter("senderUrl");
		String dirUrl = getDirectoryUrl(fileUrl);
		
		// File write channel and lock to excusively write to file
		FileWriteChannel writeChannel = null;
		boolean lock = true;
		
		// For each Period
		for (Period period : periods) {
			List<AdaptationSet> adaptationSets = period.getAllAdaptationSet();

			// For each AdaptationSet in Period
			for (AdaptationSet adaptationSet : adaptationSets) {
				List<Representation> representations =
						adaptationSet.getAllRepresentation();

				// For each Representation in AdaptationSet
				for (Representation representation : representations) {
					SegmentList segmentList = representation.getSegmentList();

					
					// Download and update init segment
					InitSegment initSegment = segmentList.getInitSegment();
					String initSegmentUrlStr = 
							dirUrl + "/" + initSegment.getSourceURL();
					AppEngineFile initSegmentFile = 
							fileService.createNewBlobFile("video/mp4");
					
					URL initSegmentUrl = new URL(initSegmentUrlStr);
					byte[] initSegmentData = 
							IOUtils.toByteArray(initSegmentUrl.openStream());
					writeChannel = 
							fileService.openWriteChannel(initSegmentFile, lock);
					
					writeChannel.write(ByteBuffer.wrap(
							initSegmentData, 0, initSegmentData.length));
					writeChannel.closeFinally();
					
					String newInitUrl = BLOBSTORE_READER_SERVICE + "?blobPath=" + 
							URLEncoder.encode(initSegmentFile.getFullPath(), "utf-8");
					
					// Test
					mpdTextFormat = mpdTextFormat.replace(
							initSegment.getSourceURL(), newInitUrl);
					
					
					// Download and update media segment
					List<MediaSegment> mediaSegments =
							segmentList.getAllMediaSegment();

					// For each media segment, create a new blob for storing
					// image after.
					for (MediaSegment mediaSegment : mediaSegments) {

						// Create a new Blob File, as a place holder for storing
						// image after.
						AppEngineFile mediaSegmentFile = null;
						while (mediaSegmentFile == null) {
							try {
								mediaSegmentFile = fileService
										.createNewBlobFile("video/mp4");
							} catch (IOException ignored) {
								// Exception will be ignored
							}
						}

						String segmentUrlStr = dirUrl + "/" + mediaSegment.getMedia();
						
						log.info("Downloading: " + segmentUrlStr);
						long startDownTime = System.nanoTime();
						URL segmentUrl = new URL(segmentUrlStr);
						byte[] segmentData = 
								IOUtils.toByteArray(segmentUrl.openStream());
						long endDownTime = System.nanoTime();
						log.info("Finish downloading " + segmentUrlStr + 
								" in: " + (endDownTime - startDownTime) + 
								" nanosecond");
						
						// Create writer to write to it
						
						writeChannel = fileService.openWriteChannel(
								mediaSegmentFile, lock);
						writeChannel.write(ByteBuffer.wrap(segmentData));
						writeChannel.closeFinally();

						String newSegmentUrl = BLOBSTORE_READER_SERVICE + "?blobPath=" + 
								URLEncoder.encode(mediaSegmentFile.getFullPath(), "utf-8");
						
						log.info("upload finished: " + mediaSegmentFile.getFullPath());
						log.info("old url: " + mediaSegment.getMedia());
						log.info("new url: " + newSegmentUrl);
						
						mpdTextFormat = mpdTextFormat.replace(
								mediaSegment.getMedia(), newSegmentUrl);
					}
				}
			}
		}
		
		PrintWriter pw = resp.getWriter();
		
		AppEngineFile mpdFile = 
				fileService.createNewBlobFile("text/plain");
		writeChannel = fileService.openWriteChannel(mpdFile, lock);
		writeChannel.write(ByteBuffer.wrap(
				mpdTextFormat.getBytes(), 0, mpdTextFormat.getBytes().length));
		writeChannel.closeFinally();
		
//		String mpdFileFullPath = "http://localhost:8888/" 
//				+ BLOBSTORE_READER_SERVICE + "?blobPath=" 
//				+ URLEncoder.encode(mpdFile.getFullPath(), "utf-8");
		
		// Add the BaseUrl property just before the fist Period tag
		mpdTextFormat = mpdTextFormat.replace(
				"<Period", 
				"<BaseURL>" + getUrlBase(request) + "</BaseURL>\n<Period");
		System.out.println(request.getServerName());
		System.out.println(request.getServerPort());
		System.out.println(request.getServletPath());
		pw.write(mpdTextFormat);
		resp.setHeader("Content-Type", "text/plain");
		pw.close();
		long endTime = System.nanoTime();
		log.info("Mpd modification end at:  "  + 
				endTime + (" (ABSOLUTE TIME)"));
	}
	
	/**
	 * Get UrlBase of the request
	 * @param req request to getUrl
	 * @return UrlBase
	 * @throws MalformedURLException
	 */
	private String getUrlBase(HttpServletRequest req) 
			throws MalformedURLException {
		URL requestUrl = new URL(req.getRequestURL().toString());
		String portString = 
				requestUrl.getPort() == -1 ? "" : ":" + requestUrl.getPort();
		return requestUrl.getProtocol() + 
				"://" + requestUrl.getHost() + portString + "/";
	}
}































