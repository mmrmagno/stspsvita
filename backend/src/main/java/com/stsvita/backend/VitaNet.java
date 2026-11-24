package com.stsvita.backend;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.net.ServerSocket;
import com.badlogic.gdx.net.ServerSocketHints;
import com.badlogic.gdx.net.Socket;
import com.badlogic.gdx.net.SocketHints;

/**
 * Permanent no-op. The game's network use (metrics upload, leaderboards) is
 * Steam-tied and out of scope; requests fail fast the way the game already
 * handles offline play.
 */
public class VitaNet implements Net {

    @Override
    public void sendHttpRequest(HttpRequest httpRequest, HttpResponseListener httpResponseListener) {
        if (httpResponseListener != null) {
            Gdx.app.postRunnable(() -> httpResponseListener.failed(
                    new UnsupportedOperationException("no network on Vita build")));
        }
    }

    @Override
    public void cancelHttpRequest(HttpRequest httpRequest) {
    }

    @Override
    public ServerSocket newServerSocket(Protocol protocol, String hostname, int port, ServerSocketHints hints) {
        throw new UnsupportedOperationException("no network on Vita build");
    }

    @Override
    public ServerSocket newServerSocket(Protocol protocol, int port, ServerSocketHints hints) {
        throw new UnsupportedOperationException("no network on Vita build");
    }

    @Override
    public Socket newClientSocket(Protocol protocol, String host, int port, SocketHints hints) {
        throw new UnsupportedOperationException("no network on Vita build");
    }

    @Override
    public boolean openURI(String uri) {
        return false;
    }
}
