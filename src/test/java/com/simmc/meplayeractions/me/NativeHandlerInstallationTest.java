package com.simmc.meplayeractions.me;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;

class NativeHandlerInstallationTest {
    @Test void theCallerReturnsPendingUntilNetworkInstallationAndMainValidationBothComplete() {
        Rig rig=new Rig();Object lease=new Object();
        assertFalse(rig.installation.request("owner",lease,()->true));assertTrue(rig.installation.pending("owner",lease));
        assertEquals(0,rig.installs);assertEquals(1,rig.network.tasks.size());
        rig.network.drain();assertEquals(1,rig.installs);assertTrue(rig.handler);assertTrue(rig.installation.pending("owner",lease));
        rig.main.drain();assertFalse(rig.installation.pending("owner",lease));assertTrue(rig.installation.request("owner",lease,()->true));
        assertEquals(1,rig.installs,"Readiness polling reuses the installed viewer handler");
    }
    @Test void cancellingBeforeInstallationCannotInstallOrBecomeReadyLater() {
        Rig rig=new Rig();Object lease=new Object();rig.installation.request("owner",lease,()->true);rig.installation.withdraw("owner");
        rig.network.drain();rig.main.drain();assertEquals(0,rig.installs);assertFalse(rig.handler);
        assertEquals(NativeHandlerInstallation.State.CLOSED,rig.installation.state());assertFalse(rig.installation.request("owner",lease,()->true));
    }
    @Test void cancellingAfterTheNetworkTaskStillRemovesTheHandlerAndDiscardsTheMainCompletion() {
        Rig rig=new Rig();Object lease=new Object();rig.installation.request("owner",lease,()->true);rig.network.drain();assertTrue(rig.handler);
        rig.installation.withdraw("owner");rig.main.drain();rig.network.drain();assertFalse(rig.handler);
        assertEquals(NativeHandlerInstallation.State.CLOSED,rig.installation.state());
    }
    @Test void aReplacedOrDisconnectedChannelCannotReuseAnOldCompletion() {
        for(boolean replaced:new boolean[]{false,true}) {
            Rig rig=new Rig();Object lease=new Object();rig.installation.request("owner",lease,()->true);rig.network.drain();
            if(replaced)rig.current=false;else rig.live=false;
            rig.main.drain();rig.network.drain();assertFalse(rig.handler);assertFalse(rig.installation.pending("owner",lease));
            assertEquals(NativeHandlerInstallation.State.CLOSED,rig.installation.state());
        }
    }
    @Test void concurrentOwnersShareOneInstallationAndOneCancellationDoesNotWithdrawTheOther() {
        Rig rig=new Rig();Object first=new Object(),second=new Object();
        rig.installation.request("one",first,()->true);rig.installation.request("two",second,()->true);assertEquals(1,rig.network.tasks.size());
        rig.installation.withdraw("one");rig.network.drain();rig.main.drain();assertTrue(rig.handler);
        assertTrue(rig.installation.request("two",second,()->true));assertEquals(1,rig.installs);
        rig.installation.withdraw("two");rig.network.drain();assertFalse(rig.handler);
    }
    @Test void ownerGenerationsAndLiveAuthorizationAreRecheckedOnTheMainThread() {
        Rig rig=new Rig();Object old=new Object(),fresh=new Object();boolean[] allowed={true};
        rig.installation.request("owner",old,()->allowed[0]);rig.installation.request("owner",fresh,()->allowed[0]);
        assertFalse(rig.installation.pending("owner",old));assertTrue(rig.installation.pending("owner",fresh));
        rig.network.drain();allowed[0]=false;rig.main.drain();rig.network.drain();assertFalse(rig.handler);
        assertEquals(NativeHandlerInstallation.State.CLOSED,rig.installation.state());
    }
    @Test void aStoppedMainSchedulerCannotLeaveAnAuthorizedHandlerBehind() {
        Queue network=new Queue();boolean[] handler={false};
        NativeHandlerInstallation installation=new NativeHandlerInstallation(network,task->{throw new RejectedExecutionException();},
                ()->true,()->true,()->{handler[0]=true;return true;},()->handler[0]=false);
        installation.request("owner",new Object(),()->true);network.drain();assertFalse(handler[0]);
        assertEquals(NativeHandlerInstallation.State.CLOSED,installation.state());
    }
    @Test void anUnavailablePipelineFallsBackWithoutReportingAnEndlessPendingRequest() {
        Rig rig=new Rig();rig.installSuccess=false;Object lease=new Object();rig.installation.request("owner",lease,()->true);
        rig.network.drain();rig.main.drain();assertFalse(rig.handler);assertFalse(rig.installation.pending("owner",lease));
        assertEquals(NativeHandlerInstallation.State.FAILED,rig.installation.state());
        assertFalse(rig.installation.request("owner",lease,()->true));assertEquals(1,rig.installs);
    }
    private static final class Queue implements Executor {
        final Deque<Runnable> tasks=new ArrayDeque<>();
        public void execute(Runnable task){tasks.addLast(task);}
        void drain(){while(!tasks.isEmpty())tasks.removeFirst().run();}
    }
    private static final class Rig {
        final Queue network=new Queue(),main=new Queue();boolean live=true,current=true,handler,installSuccess=true;int installs;
        final NativeHandlerInstallation installation=new NativeHandlerInstallation(network,main,()->live,()->current,
                ()->{installs++;handler=installSuccess;return installSuccess;},()->handler=false);
    }
}
