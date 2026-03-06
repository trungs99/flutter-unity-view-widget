package com.xraph.plugin.flutter_unity_widget

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import com.unity3d.player.IUnityPlayerLifecycleEvents
import com.unity3d.player.UnityPlayer
import java.util.concurrent.CopyOnWriteArraySet


class UnityPlayerUtils {

    companion object {
        private const val LOG_TAG = "UnityPlayerUtils"

        var controllers: ArrayList<FlutterUnityWidgetController> = ArrayList()
        var unityPlayer: UnityPlayer? = null
        var activity: Activity? = null
        var prevActivityRequestedOrientation: Int? = null

        var options: FlutterUnityWidgetOptions = FlutterUnityWidgetOptions()

        var unityPaused: Boolean = false
        var unityLoaded: Boolean = false
        var viewStaggered: Boolean = false

        private val mUnityEventListeners = CopyOnWriteArraySet<UnityEventListener>()

        fun focus() {
            try {
                val focused = unityPlayer?.frameLayout?.requestFocus() ?: return
                unityPlayer!!.windowFocusChanged(focused)
                unityPlayer!!.resume()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "focus() failed: $e")
            }
        }

        /**
         * Create a new unity player with callback
         */
        @SuppressLint("NewApi")
        fun createUnityPlayer(ule: IUnityPlayerLifecycleEvents, callback: OnCreateUnityViewCallback?) {
            if (activity == null) {
                throw java.lang.Exception("Unity activity is null")
            }

            if (unityPlayer != null) {
                unityLoaded = true
                unityPlayer!!.frameLayout?.bringToFront()
                unityPlayer!!.frameLayout?.requestLayout()
                unityPlayer!!.frameLayout?.invalidate()
                focus()
                callback?.onReady()
                return
            }

            try {
                val instance: Any = try {
                    // Unity 2023+: try UnityPlayerForActivityOrService
                    val clazz = Class.forName("com.unity3d.player.UnityPlayerForActivityOrService")
                    Log.d(LOG_TAG, "Found UnityPlayerForActivityOrService, attempting instantiation")
                    try {
                        // Unity 6000.1+: single Context constructor
                        val cons = clazz.getConstructor(android.content.Context::class.java)
                        Log.d(LOG_TAG, "Using single-arg Context constructor")
                        cons.newInstance(activity!!)
                    } catch (e: NoSuchMethodException) {
                        Log.w(LOG_TAG, "Single-arg constructor not found, trying 2-arg with IUnityPlayerLifecycleEvents", e)
                        try {
                            val cons = clazz.getConstructor(
                                android.content.Context::class.java,
                                IUnityPlayerLifecycleEvents::class.java
                            )
                            cons.newInstance(activity!!, ule)
                        } catch (e2: NoSuchMethodException) {
                            Log.w(LOG_TAG, "2-arg IUnityPlayerLifecycleEvents constructor not found, trying 2-arg with Context", e2)
                            val cons = clazz.constructors.firstOrNull()
                                ?: throw IllegalStateException("No constructors found for UnityPlayerForActivityOrService")
                            cons.newInstance(activity!!, null)
                        }
                    }
                } catch (e: ClassNotFoundException) {
                    // Fallback: Unity < 2023 uses UnityPlayer directly
                    Log.w(LOG_TAG, "UnityPlayerForActivityOrService not found, falling back to UnityPlayer via reflection", e)
                    val playerClass = Class.forName("com.unity3d.player.UnityPlayer")
                    try {
                        val cons = playerClass.getConstructor(android.content.ContextWrapper::class.java, IUnityPlayerLifecycleEvents::class.java)
                        cons.newInstance(activity!!, ule)
                    } catch (e2: Exception) {
                        try {
                            val cons = playerClass.getConstructor(android.app.Activity::class.java, IUnityPlayerLifecycleEvents::class.java)
                            cons.newInstance(activity!!, ule)
                        } catch (e3: Exception) {
                            try {
                                val cons = playerClass.getConstructor(android.content.ContextWrapper::class.java)
                                cons.newInstance(activity!!)
                            } catch (e4: Exception) {
                                val cons = playerClass.getConstructor(android.app.Activity::class.java)
                                cons.newInstance(activity!!)
                            }
                        }
                    }
                }
                unityPlayer = instance as UnityPlayer

                // Assign mUnityPlayer in the Activity, see FlutterUnityActivity.kt for more details
                if(activity is FlutterUnityActivity) {
                    (activity!! as FlutterUnityActivity)?.mUnityPlayer = (unityPlayer as java.lang.Object?);
                } else if(activity is IFlutterUnityActivity) {
                    (activity!! as IFlutterUnityActivity)?.setUnityPlayer(unityPlayer as java.lang.Object?);
                } else {
                     Log.e(LOG_TAG, "Could not set mUnityPlayer in activity");
                }
                

                // unityPlayer!!.z = (-1).toFloat()
                // addUnityViewToBackground(activity!!)
                unityLoaded = true

                if (!options.fullscreenEnabled) {
                    activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                    activity!!.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                } else {
                    activity!!.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                }

                focus()
                callback?.onReady()
            } catch (e: Exception) {
                Log.e(LOG_TAG, e.toString())
            }
        }

        fun postMessage(gameObject: String, methodName: String, message: String) {
            UnityPlayer.UnitySendMessage(gameObject, methodName, message)
        }

        fun pause() {
            try {
                if (unityPlayer != null) {
                    unityPlayer!!.pause()
                    unityPaused = true
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, e.toString())
            }
        }

        fun resume() {
            try {
                if (unityPlayer != null) {
                    unityPlayer!!.resume()
                    unityPaused = false
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, e.toString())
            }
        }

        fun unload() {
            try {
                if (unityPlayer != null) {
                    unityPlayer!!.unload()
                    unityLoaded = false
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, e.toString())
            }
        }

        fun quitPlayer() {
            try {
                if (unityPlayer != null) {
                    unityPlayer!!.destroy()
                    unityLoaded = false
                }
            } catch (e: Error) {
                e.message?.let { Log.e(LOG_TAG, it) }
            }
        }

        /**
         * Invoke by unity C#
         */
        @JvmStatic
        fun onUnitySceneLoaded(name: String, buildIndex: Int, isLoaded: Boolean, isValid: Boolean) {
            for (listener in mUnityEventListeners) {
                try {
                    listener.onSceneLoaded(name, buildIndex, isLoaded, isValid)
                } catch (e: Exception) {
                    e.message?.let { Log.e(LOG_TAG, it) }
                }
            }
        }

        /**
         * Invoke by unity C#
         */
        @JvmStatic
        fun onUnityMessage(message: String) {
            Log.d("UnityListener", "total listeners are ${mUnityEventListeners.size}")
            for (listener in mUnityEventListeners) {
                try {
                    listener.onMessage(message)
                } catch (e: Exception) {
                    e.message?.let { Log.e(LOG_TAG, it) }
                }
            }
        }

        fun addUnityEventListener(listener: UnityEventListener) {
            mUnityEventListeners.add(listener)
        }

        fun removeUnityEventListener(listener: UnityEventListener) {
            mUnityEventListeners.remove(listener)
        }

        private fun shakeActivity() {
            unityPlayer?.windowFocusChanged(true)
            if (prevActivityRequestedOrientation != null) {
                activity?.requestedOrientation = prevActivityRequestedOrientation!!
            }
        }

        fun removePlayer(controller: FlutterUnityWidgetController) {
            if (unityPlayer!!.frameLayout?.parent == controller.view) {
                if (controllers.isEmpty()) {
                    (controller.view as FrameLayout).removeView(unityPlayer!!.frameLayout)
                    pause()
                    shakeActivity()
                } else {
                    controllers[controllers.size - 1].reattachToView()
                }
            }
        }

        fun reset() {
            unityLoaded = false
        }

        fun addUnityViewToGroup(group: ViewGroup) {
             val layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
//             val layoutParams = ViewGroup.LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT)
//            val layoutParams = ViewGroup.LayoutParams(570, 770)
            group.addView(unityPlayer!!.frameLayout, layoutParams)
        }

        fun addUnityViewToBackground() {
            if (unityPlayer == null) {
                return
            }
            if (unityPlayer!!.frameLayout?.parent != null) {
                (unityPlayer!!.frameLayout?.parent as ViewGroup?)?.removeView(unityPlayer!!.frameLayout)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                unityPlayer!!.frameLayout?.z = -1f
            }
            val layoutParams = ViewGroup.LayoutParams(1, 1)
            activity!!.addContentView(unityPlayer!!.frameLayout, layoutParams)
        }
    }
}