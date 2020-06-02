/*
 * Copyright (c) 2012, Codename One and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Codename One designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *  
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 * 
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Please contact Codename One through http://www.codenameone.com/ if you 
 * need additional information or have any questions.
 */
package com.codename1.media;

import com.codename1.media.AsyncMedia.MediaStateChangeEvent;
import com.codename1.media.AsyncMedia.PlayRequest;
import com.codename1.media.AsyncMedia.State;
import com.codename1.ui.CN;
import com.codename1.ui.events.ActionListener;
import java.util.LinkedList;

/**
 * A thread-safe media channel that ensures that only Media is playing at a time.  This class will make sure
 * that the previous media has finished pausing before the next one begins to play.  This can be important
 * if you're alternating between recording audio and playing audio, as some platforms (e.g. iOS Safari) have problems
 * dealing with simulataneous use of the microphone and audio playing.
 * 
 * @author shannah
 */
public class MediaChannel {
    
    /**
     * Wrapper class for keeping track of media elements in the channel.
     */
    private class MediaWrapper {
        /**
         * The media element
         */
        private AsyncMedia media;
        
        /**
         * The play request that was returned for the media when play(media) was 
         * called.  This will be completed in updateTrack() when play is either 
         * completed or canceled.
         */
        private PlayRequest req;
        private boolean autoclean;
    }
    
    private LinkedList<MediaWrapper> queue = new LinkedList<MediaWrapper>();
    private int currMediaState;
    
    private static final int STATE_PAUSED = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_PAUSE_PENDING= 2;
    private static final int STATE_PLAY_PENDING=3;
    
    
    /**
     * A media listener that is registered with the currently-running media. 
     */
    private ActionListener<MediaStateChangeEvent> mediaListener = evt->{
        onStateChange(evt);
    };
    
    
    private void onStateChange(MediaStateChangeEvent mevt) {
        if (!CN.isEdt()) {
            CN.callSerially(()->{
                onStateChange(mevt);
            });
            return;
        }
        
        if (mevt.getNewState() == State.Paused) {
            currMediaState = STATE_PAUSED;
            updateTrack();
            
        } else if (mevt.getNewState() == State.Playing) {
            currMediaState = STATE_PLAYING;
            updateTrack();
        }
    }
    
    /**
     * Checks to see if there is any media in the queue yet to play, and advances
     * the playhead forward to the last media in the queue. If the current media isn't 
     * paused, then this simply pause it.  The media listener will recall this
     * method when the pausing is complete.
     */
    private void updateTrack() {
        
        if (queue.size() > 1) {
            // There is a pending media element in the queue.
            // We need to advance to the last media element in the queue.
            MediaWrapper currW = queue.getFirst();
            if (currW == null) {
                throw new IllegalStateException("Found current media wrapper to be null, even though the queue was not empty.  Probably a race condition");
            }
            AsyncMedia curr = currW.media;
            if (curr == null) {
                throw new IllegalStateException("Found current media to be null, even though the wrapper for it was not null.  Probably a race condition.");
            }
            //PlayRequest currReq = currW.req;
            switch (currMediaState) {
                case STATE_PAUSED:
                    // The current media is currently in paused state.
                    // We can safely move onto the next one
                    curr.removeMediaStateChangeListener(mediaListener);
                    if (currW.autoclean) {
                        curr.cleanup();
                    }
                    queue.removeFirst();
                    
                    while (queue.size() > 1) {
                        // All o
                        MediaWrapper w = queue.removeFirst();
                        if (w.req != null) {
                            w.req.cancel(false);
                            w.req = null;
                        }
                        if (w.autoclean) {
                            w.media.cleanup();
                        }
                    }
                    MediaWrapper w = queue.getFirst();
                    AsyncMedia next = w.media;
                    PlayRequest req = w.req;
                    next.addMediaStateChangeListener(mediaListener);
                    if (next.isPlaying()) {
                        throw new IllegalStateException("Media is already playing when asked to play in a channel");
                    }
                    currMediaState = STATE_PLAY_PENDING;
                    next.playAsync().onResult((res,err)->{
                        if (req != null) {
                            if (!req.isDone()) {
                                if (err != null) {
                                    req.error(err);
                                } else {
                                    req.complete(res);
                                }

                            }
                        }
                        w.req = null;
                    });
                    
                    break;
                    
                case STATE_PAUSE_PENDING:
                    break;
                case STATE_PLAY_PENDING:
                case STATE_PLAYING:
                    curr.pause();
                    break;
                    
                    
            }
        }
    }
    
    /**
     * Plays the provided media on the channel.
     * @param media The media to play.
     * @return The play request object to track when play has started.
     */
    public PlayRequest play(Media media) {
        return play(media, false);
    }
    
    /**
     * Plays the provided media on the channel.
     * @param media The media to play
     * @param autoclean If true, then the channel will automatically call cleanup() on the media 
     * when it is finished playing.
     * @return The play request object to track when play has started.
     */
    public PlayRequest play(Media media, boolean autoclean) {
        return play(MediaManager.getAsyncMedia(media), autoclean);
    }
    
    /**
     * Plays the provided media on the channel.
     * @param media The media to play.
     * @return The play request object to track when play has started.
     */
    public PlayRequest play(AsyncMedia media) {
        return play(new PlayRequest(), media, false);
    }
    

    /**
     * Plays the provided media on the channel.
     * @param req The play request object to track when the media has started playing (or if the playing is canceled).
     * @param media The media to play.
     * @param autoclean IF true, then the channel will automatically call cleanup() on the media when playing is done.
     * @return The play request object.
     * 
     */
    public PlayRequest play(PlayRequest req, AsyncMedia media, boolean autoclean) {
        if (!CN.isEdt()) {
            CN.callSerially(()->play(req, media, autoclean));
            return req;
        }
        if (media.isPlaying()) {
            throw new IllegalStateException("Media is already playing");
        }
        
        if (queue.isEmpty()) {
            MediaWrapper w = new MediaWrapper();
            w.media = media;
            w.autoclean = autoclean;
            queue.add(w);
            media.addMediaStateChangeListener(mediaListener);
            currMediaState = STATE_PLAY_PENDING;
            media.playAsync().onResult((res,err)->{
                if (req.isDone()) {
                    return;
                }
                if (err != null) {
                    req.error(err);
                } else {
                    req.complete(res);
                }
            });
            return req;
        } else {
            MediaWrapper w = new MediaWrapper();
            w.media = media;
            w.req = req;
            w.autoclean = autoclean;
            queue.add(w);
            updateTrack();
            return req;
        }
        
    }
    
    
    
}
