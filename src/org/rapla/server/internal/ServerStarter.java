package org.rapla.server.internal;

import java.util.Collection;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.servlet.ServletException;
import javax.sql.DataSource;

import org.rapla.facade.RaplaComponent;
import org.rapla.framework.RaplaException;
import org.rapla.framework.logger.Logger;
import org.rapla.server.RaplaServerExtensionPoints;
import org.rapla.server.ServerServiceContainer;
import org.rapla.server.internal.ServerServiceImpl.ServerBackendContext;
import org.rapla.servletpages.ServletRequestPreprocessor;

public class ServerStarter
{

    private ServerServiceImpl server;
    Logger logger;
    Runnable shutdownCommand;
    private ReadWriteLock restartLock = new ReentrantReadWriteLock();
    Collection<ServletRequestPreprocessor> processors;
    ServerBackendContext backendContext;
    String env_rapladatasource;
    public ServerStarter(Logger logger, RaplaJNDIContext jndi)
    {
        this.logger = logger;
        shutdownCommand = (Runnable) jndi.lookup("rapla_shutdown_command", false);

        ServerBackendContext backendContext = createBackendContext(logger, jndi);
        this.backendContext = backendContext;
        env_rapladatasource = jndi.lookupEnvString( "rapladatasource", true);
        if ( env_rapladatasource == null || env_rapladatasource.trim().length() == 0  || env_rapladatasource.startsWith( "${"))
        {
            if ( backendContext.dbDatasource != null)
            {
                env_rapladatasource = "rapladb";
            }
            else if ( backendContext.fileDatasource != null)
            {
                env_rapladatasource = "raplafile";
            }
            else
            {
                logger.warn("Neither file nor database setup configured.");
            }
            logger.info("Passed JNDI Environment rapladatasource=" + env_rapladatasource + " env_rapladb=" + backendContext.dbDatasource + " env_raplafile="+ backendContext.fileDatasource);
        }
    }

    public static ServerBackendContext createBackendContext(Logger logger, RaplaJNDIContext jndi) {
        String env_raplafile;
        DataSource env_rapladb = null;
        Object env_raplamail;
        env_raplafile = jndi.lookupEnvString("raplafile", true);
        Object lookupResource = jndi.lookupResource("jdbc/rapladb", true);
        if ( lookupResource != null)
        {
            if ( lookupResource instanceof DataSource)
            {
                env_rapladb =  (DataSource) lookupResource;
            }
            else
            {
                logger.error("Passed Object does not implement Datasource " + env_rapladb  );
            }
        }
        
      
        env_raplamail =  jndi.lookupResource( "mail/Session", false);
        if ( env_raplamail != null)
        {
            logger.info("Configured mail service via JNDI");
        }
        ServerBackendContext backendContext = new ServerBackendContext();
        backendContext.fileDatasource = env_raplafile;
        backendContext.dbDatasource = env_rapladb;
        backendContext.mailSession = env_raplamail;
        return backendContext;
    }
    
    
    
    public ReadWriteLock getRestartLock()
    {
        return restartLock;
    }
    
    //Logger logger;
    public ServerServiceImpl startServer()    throws ServletException {
        

        try
        {
            server = new ServerServiceImpl(  logger, backendContext, env_rapladatasource );
            //final RaplaContext context = raplaContainer.getContext();
            final Logger logger = server.getLogger(); 
            {
                logger.info("Rapla server started");
                if ( shutdownCommand != null)
                {
                    server.setShutdownService( new ShutdownServiceImpl());
                }
            }
            processors = server.lookupServicesFor(RaplaServerExtensionPoints.SERVLET_REQUEST_RESPONSE_PREPROCESSING_POINT);
            return server;
        }
        catch( Exception e )
        {
            logger.error(e.getMessage(), e);
            String message = "Error during initialization see logs for details: " + e.getMessage();
            if ( server != null)
            {
                server.dispose();
            }
            if ( shutdownCommand != null)
            {
                shutdownCommand.run();
            }
            
            throw new ServletException( message,e);
        }
    }
    
    public ServerServiceContainer getServer()
    {
        return server;
    }

    public void stopServer() {
        if ( server != null)
        {
            server.dispose();
        }
        if ( shutdownCommand != null)
        {
            shutdownCommand.run();
        }
    }

    private final class ShutdownServiceImpl implements ShutdownService {
        public void shutdown(final boolean restart) {
            Lock writeLock;
            try
            {
                try
                {
                    RaplaComponent.unlock( restartLock.readLock());
                }
                catch (IllegalMonitorStateException ex)
                {
                    logger.error("Error unlocking read for restart " + ex.getMessage());
                }
                writeLock = RaplaComponent.lock( restartLock.writeLock(), 60);
            }
            catch (RaplaException ex)
            { 
                logger.error("Can't restart server " + ex.getMessage());
                return;
            }
            try
            {
                //acquired = requestCount.tryAcquire(maxRequests -1,10, TimeUnit.SECONDS);
                logger.info( "Stopping  Server");
                server.dispose();
                if ( restart)
                {
                    try {
                        logger.info( "Restarting Server");
                        startServer();
                    } catch (Exception e) {
                        logger.error( "Error while restarting Server", e );
                    }
                }
                else
                {
                    stopServer();
                }
            }
            catch (Exception ex)
            {
                stopServer();
            }
            finally
            {
                RaplaComponent.unlock(writeLock);
            }
        }

    }

    public Collection<ServletRequestPreprocessor> getServletRequestPreprocessors() 
    {
        return processors;
    }

}