package hudson.plugins.starteam;

import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.remoting.VirtualChannel;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.Collection;

import com.starteam.Folder;

/**
 * A helper class for transparent checkout operations over the network. Can be
 * used with FilePath.act() to do a checkout on a remote node.
 * 
 * It allows now to create the changelog file if required.
 *
 * @author Ilkka Laukkanen <ilkka.s.laukkanen@gmail.com>
 * @author Steve Favez <sfavez@verisign.com>
 */
class StarTeamCheckoutActor implements FileCallable<Boolean>, Serializable {

	/**
	 * serial version id.
	 */
	private static final long serialVersionUID = -3748818546244161292L;

	private final FilePath changelog;
	private final BuildListener listener;
	private final String hostname;
	private final int port;
	private final String agenthost;
	private final int agentport;
	private final String user;
	private final String passwd;
	private final String projectname;
	private final String viewname;
	private final String foldername;
	private final StarTeamViewSelector config;
	private final Collection<StarTeamFilePoint> historicFilePoints;
	private final FilePath filePointFilePath;
	private final int buildNumber;

	/**
	 * 
	 * Default constructor for the checkout actor.
	 * 
	 * @param hostname
	 * 		starteam host name
	 * @param port
	 * 		starteam port
	 * @param user
	 * 		starteam connection user
	 * @param passwd
	 * 		starteam connection password
	 * @param projectname
	 * 		starteam project name
	 * @param viewname
	 * 		starteam view name
	 * @param foldername
	 * 		starteam folder name
	 * @param config
	 * 		configuration selector
	 * @param changelogFile
	 * 		change log file, as a filepath, to be able to write remotely.
	 * @param listener
	 * 		the build listener
	 */
	public StarTeamCheckoutActor(String hostname, int port, String agentHost,int agentPort,String user,
			String passwd, String projectname, String viewname,
			String foldername, StarTeamViewSelector config, FilePath changelogFile, BuildListener listener,
			AbstractBuild<?, ?> build, FilePath filePointFilePath ) {
		this.hostname = hostname;
		this.port = port;
		this.agenthost = agentHost;
		this.agentport = agentPort;
		this.user = user;
		this.passwd = passwd;
		this.projectname = projectname;
		this.viewname = viewname;
		this.foldername = foldername;
		this.changelog = changelogFile;
		this.listener = listener;
		this.config = config;
		this.filePointFilePath = filePointFilePath;
		// Would like to store build in its entirety, but it is not serializable.
		if (build == null) {
			this.buildNumber = -1;
		} else {
			this.buildNumber = build.getNumber();
		}
		
		// Previous versions stored the build object as a member of StarTeamCheckoutActor. AbstractBuild
		// objects are not serializable, therefore the starteam plugin would break when remoting to
		// another machine. Instead of storing the build object the information from the build object
		// that is needed (historicFilePoints) is stored.
		
		// Get a list of files that require updating
		Collection<StarTeamFilePoint> historicFilePoints = null;
		AbstractBuild<?, ?> lastBuild = (build == null) ? null : build.getPreviousBuild();
		if (lastBuild != null){
			try {
				File filePointFile = new File(lastBuild.getRootDir(), StarTeamConnection.FILE_POINT_FILENAME);
				if (filePointFile.exists()) {
					historicFilePoints = StarTeamFilePointFunctions.loadCollection(filePointFile);
				}
			} catch (IOException e) {
				e.printStackTrace(listener.getLogger());
			}
		}
		this.historicFilePoints = historicFilePoints;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see hudson.FilePath.FileCallable#invoke(java.io.File,
	 *      hudson.remoting.VirtualChannel)
	 */
	public Boolean invoke(File workspace, VirtualChannel channel)
			throws IOException {
		Long start=System.currentTimeMillis();
		listener.getLogger().println("Initializing StarTeam connection ...");
		StarTeamConnection connection = new StarTeamConnection(
				hostname, port, agenthost,agentport,user, passwd,
				projectname, viewname, foldername, config);	
		try {
			try {
				connection.initialize(buildNumber);
			} catch (StarTeamSCMException e) {
				listener.getLogger().println(e.getLocalizedMessage());
				return false;
			}
			listener.getLogger().println("Initialized StarTeam connection. took "+(System.currentTimeMillis()-start)+" ms.");
			
			listener.getLogger().println("Computing change set ");

			StarTeamChangeSet changeSet;
			
			Folder rootFolder = connection.getRootFolder();
			changeSet = connection.computeChangeSet(rootFolder,workspace,historicFilePoints,listener.getLogger());
			// Check 'em out
			listener.getLogger().println("performing checkout ...");

			connection.checkOut(changeSet, listener.getLogger(), filePointFilePath);

			listener.getLogger().println("creating change log file ");
			try {
				createChangeLog(changeSet, workspace, changelog, listener,
						connection);
			} catch (InterruptedException e) {
				listener.getLogger().println( "unable to create changelog file " +  e.getMessage()) ;
			}
		} catch (StarTeamSCMException e1) {
			e1.printStackTrace(listener.getLogger());
			return false;
		}catch(Exception e){
			e.printStackTrace(listener.getLogger());
			return false;
		}finally{
			connection.close();
		}
		// close the connection
		
		return true;
	}

	/**
	 * create the change log file.
	 * @param aRootFile
	 * 		starteam root file
	 * @param aChangelogFile
	 * 		the file containing changes
	 * @param aListener
	 * 		the build listener
	 * @param aConnection
	 * 		the starteam connection.
	 * @return
	 * 		true if changelog file has been created, false if not.
	 * @throws IOException
	 * @throws InterruptedException 
	 */
	protected boolean createChangeLog(
			StarTeamChangeSet changes, File aRootFile,
			FilePath aChangelogFile, BuildListener aListener, StarTeamConnection aConnection) throws IOException, InterruptedException {


		// create empty change log during call.
		if (changes == null) {
			listener
					.getLogger()
					.println(
							"last build date is null, creating an empty change log file");
			createEmptyChangeLog(aChangelogFile, aListener, "log");
			return true;
		}

		
		OutputStream os = new BufferedOutputStream(
				aChangelogFile.write());

		boolean created = false;
		try {
			created = StarTeamChangeLogBuilder.writeChangeLog(os, changes);
		} catch (Exception ex) {
			listener.getLogger().println(
					"change log creation failed due to unexpected error : "
							+ ex.getMessage());
		} finally {
			os.close();
		}

		if (!created)
			createEmptyChangeLog(aChangelogFile, aListener, "log");

		return true;
	}

	/**
	 * create the empty change log file.
	 * @param aChangelogFile
	 * @param aListener
	 * @param aRootTag
	 * @return
	 * @throws InterruptedException 
	 */
	protected final boolean createEmptyChangeLog(FilePath aChangelogFile,
			BuildListener aListener, String aRootTag) throws InterruptedException {
		try {
			OutputStreamWriter writer = new OutputStreamWriter(aChangelogFile.write(),
					Charset.forName("UTF-8"));
			
			PrintWriter printwriter = new PrintWriter( writer ) ;

			printwriter.write("<" + aRootTag + "/>");
			printwriter.close();
			return true;
		} catch (IOException e) {
			e.printStackTrace(aListener.error(e.getMessage()));
			return false;
		}
	}

}
