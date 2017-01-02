package com.rm5248.debianpbuilder;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.Proc;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.model.Project;
import hudson.scm.SCM;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jenkins plugin that builds Debian packages in a pbuilder/cowbuilder environement.
 * 
 * Based off of: https://jenkins-debian-glue.org/
 * 
 * The reason for having this as an actual plugin instead of the scripts is so that
 * we can have builders on different machines that can all communicate back to the 
 * master Jenkins instance.
 */
public class DebianPbuilder extends Builder {

    private final int numberCores;

    @DataBoundConstructor
    public DebianPbuilder(int numberCores) {
        this.numberCores = numberCores;
    }

    public int getNumberCores(){
        return numberCores;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) 
            throws InterruptedException, IOException {
        String architecture = null;
        EnvVars envVars = build.getEnvironment( listener );
        String snapshotVersion;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        DateTimeFormatter dtFormat = DateTimeFormatter.ofPattern( "YYYYMMddHHmmss");
        CowbuilderHelper cowHelp;
        FilePath binariesLocation;
        FilePath dscFile = null;
        FilePath hookdir = null;
        
        if( !launcher.isUnix() ){
            listener.getLogger().println( "Can't build: not on Unix-like system" );
            return false;
        }
        
        if( !ensureDpkgParseChangelogIsValid(build, launcher, listener) ){
            return false;
        }
        
        if( !isDebianPackage(build, launcher, listener) ){
            listener.getLogger().println( "We do not appear to have an actual package" );
            return false;
        }
        
        String packageName = getDpkgField(build, launcher, listener, "source" );
        if( packageName == null ){
            listener.getLogger().println( "Unable to get package name(source) from changelog" );
            return false;
        }
        
        String version = getDpkgField(build, launcher, listener, "version" );
        if( version == null ){
            listener.getLogger().println( "Unable to get version from changelog" );
            return false;
        }
        
        String distribution = getDpkgField(build, launcher, listener, "distribution" );
        if( distribution == null ){
            listener.getLogger().println( "Unable to get distribution from changelog" );
            return false;
        }
        
        if( distribution.equalsIgnoreCase( "unreleased" ) ){
            //do not raise the version number if this is an unreleased version
            snapshotVersion = version + "~";
        }else{
            snapshotVersion = version + "+0";
        }
        snapshotVersion += now.format( dtFormat );
       

        if( envVars.containsKey( "architecture" ) ){
            architecture = envVars.get( "architecture" );
        }

        if( envVars.containsKey( "GIT_COMMIT" ) ){
            snapshotVersion += ".git" + envVars.get( "GIT_COMMIT" ).substring( 0, 7 );
        }

        if( envVars.containsKey( "SVN_REVISION" ) ){
            snapshotVersion += ".svn" + envVars.get( "SVN_REVISION" );
        }
        snapshotVersion += "." + build.getNumber();
        
        listener.getLogger().println( "Snapshot version: " + snapshotVersion );
        
 
        updateChangelog(launcher, build.getWorkspace().child( "source" ).child( "debian" ).child( "changelog" ),
                packageName, snapshotVersion);

        tarSources(build, launcher, listener);

        generateChanges(build, launcher, listener, packageName, snapshotVersion);
        
        binariesLocation = build.getWorkspace().createTempDir( "binaries", null );
        hookdir = build.getWorkspace().createTempFile( "hookdir", null );
        
        for( FilePath path : build.getWorkspace().list() ){
            if( path.getName().endsWith( ".dsc" ) ){
                if( dscFile != null ){
                    listener.getLogger().println( "More than one dsc file found, aborting build" );
                    return false;
                }
                dscFile = path;
            }
        }
        
        if( distribution.equalsIgnoreCase( "UNRELEASED" ) ){
            distribution = getStdoutOfProcess(build, launcher, listener, "lsb_release", "--short", "--codename" );
        }
        
        //Now that we have our sources, run debootstrap
        cowHelp = new CowbuilderHelper(build, launcher, listener.getLogger(),
                architecture, distribution, 
                new File( hookdir.toURI() ).getAbsolutePath() );
        
        if( !cowHelp.createOrUpdateCowbuilder() ){
            return false;
        }
        
        if( !cowHelp.buildInEnvironment( binariesLocation, dscFile, getNumberOfCores() ) ){
            return false;
        }
        
        
        Map<String,String> files = new HashMap<String,String>();
        for( FilePath path : binariesLocation.list() ){
            files.put( path.getName(), path.getName() );
        }
        
        build.getArtifactManager().archive( binariesLocation, launcher, listener, files );
                
        return true;
    }
    
    /**
     * Ensure that dpkg-parsechangelog is installed and >= 1.17
     * @return 
     */
    private boolean ensureDpkgParseChangelogIsValid(AbstractBuild build, Launcher launcher, BuildListener listener){
        Launcher.ProcStarter procStarter = launcher
            .launch()
            .cmdAsSingleString( "dpkg-parsechangelog --version" )
            .readStdout();
        Proc proc = null;
        try{
            proc = procStarter.start();
            procStarter.join();
        }catch( IOException | InterruptedException ex ){
            listener.getLogger().println( ex );
            return false;
        }
        
        Scanner scan = new Scanner( proc.getStdout() );
        
        String line = scan.nextLine();
        
        String pattern = ".*version (\\d+)\\.(\\d+)\\.(\\d+)";
        Matcher m = Pattern.compile( pattern ).matcher( line );
        
        if( m.find() ){
            int major = Integer.parseInt( m.group( 1 ) );
            int minor = Integer.parseInt( m.group( 2 ) );
            int patch = Integer.parseInt( m.group( 3 ) );
            
            return ( major >= 1 && minor >= 17 );
        }
        
        listener.getLogger().println( "Can't continue: dpkg-parsechangelog is "
                + "not new enough(needs to be at least 1.17.0)" );
        
        return false;
    }
    
    /**
     * Basic check to see if we are actually trying to build a debian package.
     * 
     * @param build
     * @param launcher
     * @param listener
     * @return 
     */
    private boolean isDebianPackage( AbstractBuild build, Launcher launcher, BuildListener listener ){
        Launcher.ProcStarter procStarter = launcher
            .launch()
            .pwd( build.getWorkspace().child( "source" ) )
            .cmdAsSingleString( "dpkg-parsechangelog --count 1" )
            .readStdout();
        int status;
        try{
            procStarter.start();
            status = procStarter.join();
        }catch( IOException | InterruptedException ex ){
            listener.getLogger().print( ex );
            return false;
        }
        
        if( status != 0 ){
            return false;
        }
        
        return true;
    }
    
    private String getDpkgField( AbstractBuild build, Launcher launcher, BuildListener listener, String fieldName ){
        Launcher.ProcStarter procStarter = launcher
            .launch()
            .pwd( build.getWorkspace().child( "source" ) )
            .cmds( "dpkg-parsechangelog", "--show-field", fieldName )
            .readStdout();
        int status;
        Proc proc = null;
        try{
            proc = procStarter.start();
            status = procStarter.join();
        }catch( IOException | InterruptedException ex ){
            listener.getLogger().print( ex );
            return null;
        }
        
        if( status != 0 ){
            return null;
        }
        
        Scanner scan = new Scanner( proc.getStdout() );
        
        return scan.nextLine();
    }
    
    /**
     * Force update the changelog.  Don't use any of the debian scripts to update,
     * since we just want new stuff + old.  This is what jenkins-debian-glue does with SVN snapshots.
     * 
     */
    private void updateChangelog( Launcher launcher, FilePath changelog, String packageName, String snapshotVersion ) throws IOException, InterruptedException {
        Scanner scan = new Scanner( changelog.read() );
        StringBuilder strBuild = new StringBuilder();
        String debEmail = "Debian Pbuilder Autobuilder <" +
                getEmail(launcher) + ">";
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        DateTimeFormatter dtFormat = DateTimeFormatter.ofPattern( "ccc, dd MMM YYYY HH:mm:ss Z");
                
        strBuild.append( packageName );
        strBuild.append( " (" );
        strBuild.append( snapshotVersion );
        strBuild.append( ") UNRELEASED; urgency=low" );
        strBuild.append( System.lineSeparator() );
        strBuild.append( System.lineSeparator() );
        
        strBuild.append( "  ** SNAPSHOT Build **" );
        strBuild.append( System.lineSeparator() );
        strBuild.append( System.lineSeparator() );
        
        strBuild.append( " -- " );
        strBuild.append( debEmail );
        strBuild.append( "  " );
        strBuild.append( now.format( dtFormat ) );
        strBuild.append( System.lineSeparator() );
        strBuild.append( System.lineSeparator() );
        
        while( scan.hasNextLine() ){
            strBuild.append( scan.nextLine() );
            strBuild.append( System.lineSeparator() );
        }
        
        scan.close();
                
        Writer w = new OutputStreamWriter( changelog.write() );
        w.write( strBuild.toString() );
        w.close();
    }
    
    private String getEmail( Launcher launcher ) throws IOException, InterruptedException{
        String email = getDescriptor().getJenkinsEmail();
        
        if( email == null || email.length() == 0 ){
            Launcher.ProcStarter procStarter = launcher
                .launch()
                .cmds( "hostname" )
                .readStdout();
            int status;
            Proc proc = null;
            proc = procStarter.start();
            status = procStarter.join();
            Scanner scan = new Scanner( proc.getStdout() );
            
            return "jenkins@" + scan.nextLine();
        }
        
        return email;
    }
    
    private boolean tarSources( AbstractBuild build, Launcher launcher, BuildListener listener ) 
        throws IOException, InterruptedException{
        Launcher.ProcStarter procStarter = launcher
            .launch()
            .pwd( build.getWorkspace() )
            .cmds( "dpkg-source", "-b", "source" );
        int status;
        Proc proc = null;
        proc = procStarter.start();
        status = procStarter.join();
        
        if( status != 0 ){
            return false;
        }
        
        return true;
    }
    
    
    
    private boolean generateChanges( AbstractBuild build, Launcher launcher, BuildListener listener, 
            String packageName, String packageVersion ) throws IOException, InterruptedException {
        Launcher.ProcStarter procStarter = launcher
            .launch()
            .pwd( build.getWorkspace() )
            .cmds( "dpkg-genchanges", "-u.", "source" )
            .readStdout();
        int status;
        Proc proc = null;
        try{
            proc = procStarter.start();
            status = procStarter.join();
        }catch( IOException | InterruptedException ex ){
            listener.getLogger().print( ex );
            return false;
        }
        
        if( status != 0 ){
            return false;
        }
        
        Scanner scan = new Scanner( proc.getStdout() );
        Writer w = new OutputStreamWriter( build.getWorkspace().child( packageName + "_" + packageVersion ).write() );
        while( scan.hasNextLine() ){
            w.write( scan.nextLine() );
            w.write( System.lineSeparator() );
        }
        
        return true;
    }
    
    private String getStdoutOfProcess( AbstractBuild build, Launcher launcher, BuildListener listener, String ... args )
        throws IOException, InterruptedException {
        StringBuilder toRet = new StringBuilder();
        Launcher.ProcStarter procStarter = launcher
            .launch()
            .pwd( build.getWorkspace() )
            .cmds( args )
            .readStdout();
        int status;
        Proc proc = null;
        proc = procStarter.start();
        status = procStarter.join();
        
        if( status != 0 ){
            return "sid";
        }
        
        Scanner scan = new Scanner( proc.getStdout() );
        while( scan.hasNextLine() ){
            toRet.append( scan.nextLine() );
        }
        
        return toRet.toString();
    }
    
    private int getNumberOfCores(){
        if( numberCores == -1 ){
            return Runtime.getRuntime().availableProcessors();
        }else if( numberCores < -1 ){
            return 1;
        }else if( numberCores == 0 ){
            return 1;
        }else{
            return numberCores;
        }
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link DebianPbuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        
        private String jenkinsEmail;

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         */
        public FormValidation doCheckName(@QueryParameter String value)
                throws IOException, ServletException {
            return FormValidation.ok();
        }
        
        public FormValidation doCheckNumberCores(@QueryParameter String value ){
            int i;
            try{
                i = Integer.parseInt( value );
            }catch( NumberFormatException ex ){
                return FormValidation.error( "That is not a valid number" );
            }
            
            if( i == 0 ){
                return FormValidation.error( "Number of cores cannot be 0" );
            }
            
            if( i < 0 && i != -1 ){
                return FormValidation.error( "Use -1 to use all available cores" );
            }
            
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Debian Pbuilder";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            jenkinsEmail = formData.getString( "jenkinsEmail" );
            save();
            return super.configure(req,formData);
        }
        
        public String getJenkinsEmail(){
            return jenkinsEmail;
        }
    }
}
