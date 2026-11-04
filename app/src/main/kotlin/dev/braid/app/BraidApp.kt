package dev.braid.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import dev.braid.app.workspace.WorkspaceManager
import javax.inject.Inject

@HiltAndroidApp
class BraidApp : Application() {
    @Inject lateinit var workspaces: WorkspaceManager

    override fun onCreate() {
        super.onCreate()
        // Networking follows *process* visibility (not a single Activity): rotating or opening the
        // scanner must not drop peer sessions, but backgrounding the app releases the multicast
        // lock and sockets. See "Background sync" in details.md for why there is no service.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = workspaces.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = workspaces.setForeground(false)
        })
    }
}
