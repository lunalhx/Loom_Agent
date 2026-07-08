package cn.lunalhx.ai.infrastructure.adapter.vector;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

public class VecExtensionLoadingDataSource implements DataSource {

    private final DataSource delegate;
    private final String extensionPath;
    private final Object loadLock = new Object();
    private volatile boolean extensionLoaded;

    public VecExtensionLoadingDataSource(DataSource delegate, String extensionPath) {
        this.delegate = delegate;
        this.extensionPath = extensionPath;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection conn = delegate.getConnection();
        loadExtension(conn);
        return conn;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection conn = delegate.getConnection(username, password);
        loadExtension(conn);
        return conn;
    }

    private void loadExtension(Connection conn) throws SQLException {
        if (extensionPath == null || extensionPath.isBlank()) {
            return;
        }
        if (extensionLoaded) {
            return;
        }
        synchronized (loadLock) {
            if (extensionLoaded) {
                return;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT load_extension('" + extensionPath + "')");
            }
            extensionLoaded = true;
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }

    @Override
    public Logger getParentLogger() {
        throw new UnsupportedOperationException("getParentLogger not supported");
    }
}
