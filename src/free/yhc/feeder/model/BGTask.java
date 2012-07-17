/*****************************************************************************
 *    Copyright (C) 2012 Younghyung Cho. <yhcting77@gmail.com>
 *
 *    This file is part of Feeder.
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as
 *    published by the Free Software Foundation either version 3 of the
 *    License, or (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License
 *    (<http://www.gnu.org/licenses/lgpl.html>) for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *****************************************************************************/

package free.yhc.feeder.model;

import static free.yhc.feeder.model.Utils.eAssert;
import static free.yhc.feeder.model.Utils.logI;
import static free.yhc.feeder.model.Utils.logW;

import java.util.Iterator;
import java.util.LinkedList;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.PowerManager;

public class BGTask<RunParam, CancelParam> extends Thread {
    public static int OPT_WAKELOCK  = 0x01;
    public static int OPT_WIFILOCK  = 0x02;


    private String           nick = null; // can be used several purpose.
    private Handler          ownerHandler = new Handler();
    private LinkedList<EventListener> listenerList = new LinkedList<EventListener>();
    private volatile Err     result  = Err.NO_ERR;

    // Flags
    private volatile boolean cancelled = false;

    private int              opt         = 0;
    private RunParam         runParam    = null;
    private CancelParam      cancelParam = null;

    private static final String     WLTAG = "BGTask";
    private Object                  wlmx = new Object(); // Wakelock mutex
    private PowerManager.WakeLock   wl = null;
    private WifiManager.WifiLock    wfl = null;

    public interface OnEvent<RunParam, CancelParam> {
        // return : false (DO NOT run this task)
        void onPreRun  (BGTask<RunParam, CancelParam> task);
        void onPostRun (BGTask<RunParam, CancelParam> task, Err result);
        void onCancel  (BGTask<RunParam, CancelParam> task, CancelParam param);
        void onProgress(BGTask<RunParam, CancelParam> task, long progress);
    }

    public static class EventListener {
        private Object      key;
        private Handler     handler;
        private OnEvent     onEvent;

        EventListener(Object key, Handler handler, OnEvent onEvent) {
            this.key = key;
            this.handler = handler;
            this.onEvent = onEvent;
        }

        Object getKey() {
            return key;
        }

        Handler getHandler() {
            return handler;
        }

        OnEvent getOnEvent() {
            return onEvent;
        }
    }

    private class BGTaskPost implements Runnable {
        private boolean bCancelled;
        BGTaskPost(boolean bCancelled) {
            this.bCancelled = bCancelled;
        }

        @Override
        public void run() {
            if (BGTask.this.isAlive()) {
                //logI("BGTask : REPOST : wait for task is Done");
                // post message continuously may drop responsibility for user, in case that ownerHandler is attached to UI thread.
                // To increase responsibility, post message with some delay.
                ownerHandler.postDelayed(new BGTaskPost(bCancelled), 10);
                return;
            }

            //logI("BGTask : REAL POST!!! cancelled: " + bCancelled + ", NR Listener: " + getListeners().length);
            if (bCancelled) {
                onEarlyCancel(cancelParam);
                for (EventListener listener : getListeners()) {
                    final OnEvent onEvent = listener.getOnEvent();
                    //logI("BGTask : Post(Cancel) to onEvent : " + onEvent.toString());
                    listener.getHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            onEvent.onCancel(BGTask.this, cancelParam);
                        }
                    });
                }
                ownerHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onLateCancel(cancelParam);
                    }
                });
            } else {
                onEarlyPostRun(result);
                for (EventListener listener : getListeners()) {
                    final OnEvent onEvent = listener.getOnEvent();
                    //logI("BGTask : Post(Post) to onEvent : " + onEvent.toString());
                    listener.getHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            //logI("+++ BGTask : postRun : " + onEvent.toString());
                            onEvent.onPostRun(BGTask.this, result);
                        }
                    });
                }
                ownerHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onLatePostRun(result);
                    }
                });
            }
        }
    }

    public BGTask(RunParam arg, int option) {
        super();
        // NOTE
        // Even if, BGTask is designed considering multi-threaded environment,
        //   to simplify synchronization issue, only UI Thread can create BG Task.
        // (Actually, this is NOT constraints for BGTask, but for Feeder app.
        eAssert(Utils.isUiThread());
        runParam = arg;
        opt = option;
    }

    // ==========================================
    // Private
    // ==========================================
    private void
    releaseWLock() {
        synchronized (wlmx) {
            if (null != wl) {
                //logI("< WakeLock >" + WLTAG + nick + " >>>>>> release");
                wl.release();
                wl = null;
            }

            if (null != wfl) {
                wfl.release();
                wfl = null;
            }
        }
    }

    private void
    aquireWLock() {
        synchronized (wlmx) {
            // acquire wakelock/wifilock if needed
            if (0 != (OPT_WAKELOCK & opt)) {
                //logI("< WakeLock >" + WLTAG + nick + " <<<<<< acquire");
                wl = ((PowerManager)Utils.getAppContext().getSystemService(Context.POWER_SERVICE))
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WLTAG + nick);
                wl.acquire();
            }

            if (0 != (OPT_WIFILOCK & opt)) {
                wfl = ((WifiManager)Utils.getAppContext().getSystemService(Context.WIFI_SERVICE))
                        .createWifiLock(WifiManager.WIFI_MODE_FULL, WLTAG + nick);
                wfl.acquire();
            }
        }
    }

    private EventListener[]
    getListeners() {
        synchronized (listenerList) {
            return listenerList.toArray(new EventListener[0]);
        }
    }
    // ==========================================
    // Protected
    // ==========================================

    // ==========================================
    // Package Private
    // ==========================================
    /**
     * Set result of this BGTask to 'Err.NoErr'.
     */
    void
    resetResult() {
        eAssert(!isAlive());
        result = Err.NO_ERR;
    }

    void
    setNick(String nick) {
        this.nick = nick;
    }

    // ==========================================
    // Open Interface
    // ==========================================
    public String
    getNick() {
        return nick;
    }

    /**
     * Originally, isInterupted() is used.
     * But, based on my test experience, isInterrupted seems to reflect only BG Thread state
     *   (Not Thread object's state).
     * So, with some sensitive timing condition, calling Thread.isInterrupted() returns false,
     *   if it is called right after Thread.interrupt() is called.
     * (I didn't test and verify it strictly yet.)
     * To avoid this, isCancelled() is newly introduced and used.
     * @return
     */
    public boolean
    isCancelled() {
        return cancelled;
    }

    public Err
    getResult() {
        eAssert(null != result);
        return result;
    }

    /**
     * Register event listener with it's key value.
     * Newly added event listener will be added to the last of listener list.
     * (event listener will be notified in order of listener list.)
     *
     * Owner of given listener will be set into current Thread automatically.
     *
     * Key value is used to find event listener (onEvent).
     * Several event listener may share one key value.
     * Event callback will be called on caller's thread message loop.
     * @param key
     * @param onEvent
     */
    public void
    registerEventListener(Object key, OnEvent onEvent) {
        // See comments regarding 'eAssert' at BGTask constructor.
        eAssert(Utils.isUiThread());

        if (ownerHandler.getLooper().getThread() != Thread.currentThread())
            logW("BGTask IMPORTANT : owner thread is different with event listener thread");

        // This is atomic expression
        synchronized (listenerList) {
            //logI("BGTask : registerEventListener : key(" + key + ") onEvent(" + onEvent + ")");
            EventListener listener = new EventListener(key, new Handler(), onEvent);
            listenerList.addLast(listener);
        }
    }

    /**
     * Same with 'registerEventListener' except for that newly added listener
     *   will be added to the first of listener list.
     * Event callback will be called on caller's thread message loop.
     * @param key
     * @param onEvent
     */
    public void
    registerPriorEventListener(Object key, OnEvent onEvent) {
        // See comments regarding 'eAssert' at BGTask constructor.
        eAssert(Utils.isUiThread());

        if (ownerHandler.getLooper().getThread() != Thread.currentThread())
            logW("BGTask IMPORTANT : owner thread is different with event listener thread");

        synchronized (listenerList) {
            EventListener listener = new EventListener(key, new Handler(), onEvent);
            listenerList.addFirst(listener);
        }
    }

    /**
     * Unregister all event listeners whose owner is given thread.
     * @param owner
     */
    public void
    unregisterEventListener(Thread owner) {
        synchronized (listenerList) {
            Iterator<EventListener> iter = listenerList.iterator();
            while (iter.hasNext()) {
                EventListener listener = iter.next();
                if (listener.getHandler().getLooper().getThread() == owner) {
                    //logI("BGTask : unregisterEventListener : (" + listener.key + ") onEvent(" + listener.onEvent + ")");
                    iter.remove();
                }
            }
        }
    }

    /**
     * Unregister event listener whose owner and object match.
     * @param owner
     * @param onEventKey
     */
    public void
    unregisterEventListener(Thread owner, Object onEventKey) {
        synchronized (listenerList) {
            Iterator<EventListener> iter = listenerList.iterator();
            while (iter.hasNext()) {
                EventListener listener = iter.next();
                if (listener.getHandler().getLooper().getThread() == owner
                    && listener.getKey() == onEventKey) {
                    //logI("BGTask : unregisterEventListener : (" + listener.key + ") onEvent(" + listener.onEvent + ")");
                    iter.remove();
                }
            }
        }
    }

    /**
     * Make event listener list empty.
     * (Same as unregistering all listeners.)
     */
    public void
    clearEventListener() {
        synchronized (listenerList) {
            //logI("BGTask : clearEventListener");
            listenerList.clear();
        }
    }

    // main background task to do.
    protected Err
    doBGTask(RunParam runParam)
        throws FeederException {
        return Err.NO_ERR;
    }

    /**
     * This will be called prior to all other registered listeners whose owner thread
     *   is same with this Thread's owner (thread context in which this Thread object
     *   is created (owner thread)).
     * If other listener's owner thread is different with the owner of this Thread object,
     *   than order of listener's execution is unknown (it's dependent on thread scheduling.)
     */
    protected void
    onEarlyPreRun() {}

    /**
     * This will be called after all other registered listeners whose owner thread
     *   is same with this Thread's owner (thread context in which this Thread object
     *   is created (owner thread)) is executed.
     * If other listener's owner thread is different with the owner of this Thread object,
     *   than order of listener's execution is unknown (it's dependent on thread scheduling.)
     */
    protected void
    onLatePreRun() {}

    /**
     * This will be called prior to all other registered listeners whose owner thread
     *   is same with this Thread's owner (thread context in which this Thread object
     *   is created (owner thread)).
     * If other listener's owner thread is different with the owner of this Thread object,
     *   than order of listener's execution is unknown (it's dependent on thread scheduling.)
     */
    protected void
    onEarlyPostRun (Err result) {}

    /**
     * This will be called after all other registered listeners whose owner thread
     *   is same with this Thread's owner (thread context in which this Thread object
     *   is created (owner thread)) is executed.
     * If other listener's owner thread is different with the owner of this Thread object,
     *   than order of listener's execution is unknown (it's dependent on thread scheduling.)
     */
    protected void
    onLatePostRun (Err result) {
        releaseWLock();
    }

    /**
     * This will be called prior to all other registered listeners whose owner thread
     *   is same with this Thread's owner (thread context in which this Thread object
     *   is created (owner thread)).
     * If other listener's owner thread is different with the owner of this Thread object,
     *   than order of listener's execution is unknown (it's dependent on thread scheduling.)
     */
    protected void
    onEarlyCancel(CancelParam param) {}

    /**
     * This will be called after all other registered listeners whose owner thread
     *   is same with this Thread's owner (thread context in which this Thread object
     *   is created (owner thread)) is executed.
     * If other listener's owner thread is different with the owner of this Thread object,
     *   than order of listener's execution is unknown (it's dependent on thread scheduling.)
     */
    protected void
    onLateCancel(CancelParam param) {
        releaseWLock();
    }

    /**
     * This will be called prior to all other registered listeners whose owner thread
     *   is same with this Thread's owner (thread context in which this Thread object
     *   is created (owner thread)).
     * If other listener's owner thread is different with the owner of this Thread object,
     *   than order of listener's execution is unknown (it's dependent on thread scheduling.)
     */
    protected void
    onProgress(long progress) {}

    // This SHOULD NOT BE CALLED DIRECTLY!!!
    // 'start' SHOULD be called only at BGTaskManager
    /**
     * THIS SHOULD BE CALLED ONLY BY 'BGTaskManager'.
     * Start background task
     */
    @Override
    public final void
    start() {
        // Current algorithm for managing BGTask, doesn't allow 'null' nick.
        // See BGTaskManager for details.
        //
        // Trying to running BGTask that has null nick value, means
        //   "try to start BGTask that is not managed".
        // And this is out of expectation.
        eAssert(null != nick);
        aquireWLock();
        ownerHandler.post(new Runnable() {
            @Override
            public void run() {
                onEarlyPreRun();
                for (EventListener listener : getListeners()) {
                    final OnEvent onEvent = listener.getOnEvent();
                    listener.getHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            onEvent.onPreRun(BGTask.this);
                        }
                    });
                }
                ownerHandler.post(new Runnable() {
                   @Override
                   public void run() {
                       onLatePreRun();
                   }
                });
                BGTask.super.start();
            }
        });
    }

    /**
     * DO NOT USE CALL THIS FUNCTION OUTSIDE
     *   even if it's visibility is 'public'
     * (visibility is 'public' only because this is overridden.)
     */
    @Override
    public final void
    run() {
        // Task may be cancelled before real-running.
        if (cancelled)
            return;

        logI("BGTask : BGJobs started");
        boolean bInterrupted = false;
        try {
            result =  doBGTask(runParam);
            eAssert(null != result);
        } catch (FeederException e) {
            if (Err.INTERRUPTED == e.getError())
                bInterrupted = true;
        }

        if (!bInterrupted)
            bInterrupted = Thread.currentThread().isInterrupted();

        if (cancelled && Err.NO_ERR != result)
            result = Err.USER_CANCELLED;

        //logI("BGTask : BGJobs done - try to post! : " + result.name() + " interrupted(" + bInterrupted + ")");

        if (bInterrupted
            || Err.INTERRUPTED == result
            || Err.USER_CANCELLED == result)
            ownerHandler.post(new BGTaskPost(true));
        else
            ownerHandler.post(new BGTaskPost(false));
    }

    /**
     * THIS SHOULD BE CALLED ONLY BY 'BGTaskManager'.
     * Cancel background task.
     * This will interrupt background task.
     * @param param
     * @return
     */
    boolean
    cancel(CancelParam param) {
        if (cancelled)
            return true; // nothing to do.

        cancelled = true;
        result = Err.USER_CANCELLED;
        cancelParam = param;
        //logI("BGTask : cancel()");

        if (Thread.State.NEW == getState()) {
            // Task is not even started.
            // We can cancel it!.
            interrupt();
            ownerHandler.post(new BGTaskPost(true));
        } else if (Thread.State.TERMINATED == getState())
            // Task is already finished.
            // So, we cannot cancel it!
            return false;
        else
            // Thread is alive!
            interrupt();

        return true;
    }

    /**
     * This will trigger onProgress listener.
     * onProgress listener will be called in their owner's thread context.
     */
    public void
    publishProgress(final long progress) {
        ownerHandler.post(new Runnable() {
            @Override
            public void run() {
                onProgress(progress);
            }
        });
        for (EventListener listener : getListeners()) {
            final OnEvent onEvent = listener.getOnEvent();
            listener.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    onEvent.onProgress(BGTask.this, progress);
                }
            });
        }
    }
}
