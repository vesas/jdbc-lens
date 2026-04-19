package fi.vesas.jdbcprof.sample.service;

import fi.vesas.jdbcprof.sample.dao.SessionDao;

import java.sql.Connection;
import java.sql.SQLException;

public final class SessionService {

    private final SessionDao sessions;

    public SessionService(SessionDao sessions) {
        this.sessions = sessions;
    }

    public void refresh(Connection c, int... sessionIds) throws SQLException {
        for (int id : sessionIds) {
            sessions.touchLastSeen(c, id);
        }
    }
}
