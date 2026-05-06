package fi.vesas.jdbclens.sample.service;

import java.sql.Connection;
import java.sql.SQLException;

import fi.vesas.jdbclens.sample.dao.SessionDao;

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
