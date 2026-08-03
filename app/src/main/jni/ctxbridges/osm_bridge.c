//
// Created by maks on 18.10.2023.
//
#include <malloc.h>
#include <pthread.h>
#include <string.h>
#include <environ/environ.h>
#include "osm_bridge.h"
#define TAG __FILE_NAME__
static __thread osm_render_window_t* currentBundle;
static pthread_mutex_t g_osm_surface_mutex = PTHREAD_MUTEX_INITIALIZER;

static void osm_finish_surface_switch(osm_render_window_t* bundle, uint64_t consumedGeneration) {
    pthread_mutex_lock(&g_osm_surface_mutex);
    bundle->activeSurfaceGeneration = consumedGeneration;
    char nextState = bundle->newSurfaceGeneration != 0
        ? STATE_RENDERER_NEW_WINDOW
        : STATE_RENDERER_ALIVE;
    atomic_store_explicit(&bundle->state, nextState, memory_order_release);
    pthread_mutex_unlock(&g_osm_surface_mutex);
    pojavAcknowledgeBridgeWindowGeneration(consumedGeneration);
}
// a tiny buffer for rendering when there's nowhere t render
static char no_render_buffer[4];

// Its not in a .h file because it is not supposed to be used outsife of this file.
void setNativeWindowSwapInterval(struct ANativeWindow* nativeWindow, int swapInterval);

bool osm_init() {
    if(!dlsym_OSMesa()) return false;
    return true;
}

osm_render_window_t* osm_get_current() {
    return currentBundle;
}

osm_render_window_t* osm_init_context(osm_render_window_t* share) {
    osm_render_window_t* render_window = malloc(sizeof(osm_render_window_t));
    if(render_window == NULL) return NULL;
    memset(render_window, 0, sizeof(osm_render_window_t));
    OSMesaContext osmesa_share = NULL;
    if(share != NULL) osmesa_share = share->context;
    OSMesaContext context = OSMesaCreateContext_p(GL_RGBA, osmesa_share);
    if(context == NULL) {
        free(render_window);
        return NULL;
    }
    render_window->context = context;
    return render_window;
}

void osm_set_no_render_buffer(ANativeWindow_Buffer* buffer) {
    buffer->bits = &no_render_buffer;
    buffer->width = 1;
    buffer->height = 1;
    buffer->stride = 0;
}

void osm_swap_surfaces(osm_render_window_t* bundle) {
    pthread_mutex_lock(&g_osm_surface_mutex);
    ANativeWindow* nextSurface = bundle->newNativeSurface;
    ANativeWindow* previousSurface = bundle->nativeSurface;
    bool previousRenderingDisabled = bundle->disable_rendering;
    uint64_t nextGeneration = bundle->newSurfaceGeneration;
    bundle->newNativeSurface = NULL;
    bundle->newSurfaceGeneration = 0;
    bundle->nativeSurface = nextSurface;
    pthread_mutex_unlock(&g_osm_surface_mutex);

    if(previousSurface != NULL) {
        if(!previousRenderingDisabled) {
            ;
            ANativeWindow_unlockAndPost(previousSurface);
        }
        ANativeWindow_release(previousSurface);
    }
    if(nextSurface != NULL) {
        ;
        ANativeWindow_setBuffersGeometry(nextSurface, 0, 0, WINDOW_FORMAT_RGBX_8888);
        bundle->disable_rendering = false;
    }else {
        ;
        osm_set_no_render_buffer(&bundle->buffer);
        bundle->disable_rendering = true;
    }
    osm_finish_surface_switch(bundle, nextGeneration);
}

void osm_release_window() {
    pthread_mutex_lock(&g_osm_surface_mutex);
    if (currentBundle->newNativeSurface != NULL) {
        pojavReleaseBridgeWindow(currentBundle->newNativeSurface);
    }
    currentBundle->newNativeSurface = NULL;
    pthread_mutex_unlock(&g_osm_surface_mutex);
    osm_swap_surfaces(currentBundle);
}

void osm_apply_current_ll() {
    ANativeWindow_Buffer* buffer = &currentBundle->buffer;
    OSMesaMakeCurrent_p(currentBundle->context, buffer->bits, GL_UNSIGNED_BYTE, buffer->width, buffer->height);
    if(buffer->stride != currentBundle->last_stride)
        OSMesaPixelStore_p(OSMESA_ROW_LENGTH, buffer->stride);
    currentBundle->last_stride = buffer->stride;
}

void osm_make_current(osm_render_window_t* bundle) {
    if(bundle == NULL) {
        //technically this does nothing as its not possible to unbind a context in OSMesa
        OSMesaMakeCurrent_p(NULL, NULL, 0, 0, 0);
        currentBundle = NULL;
        return;
    }
    bool hasSetMainWindow = false;
    currentBundle = bundle;
    pthread_mutex_lock(&g_osm_surface_mutex);
    if(pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = (basic_render_window_t*) bundle;
        hasSetMainWindow = true;
    }
    pthread_mutex_unlock(&g_osm_surface_mutex);
    if(hasSetMainWindow) {
        ;
        uint64_t generation = 0;
        ANativeWindow* window = pojavAcquireBridgeWindowWithGeneration(&generation);
        pthread_mutex_lock(&g_osm_surface_mutex);
        if (pojav_environ->mainWindowBundle == (basic_render_window_t*)bundle &&
            generation >= bundle->latestSurfaceGeneration) {
            if (bundle->newNativeSurface != NULL) {
                pojavReleaseBridgeWindow(bundle->newNativeSurface);
            }
            bundle->newNativeSurface = window;
            bundle->newSurfaceGeneration = generation;
            bundle->latestSurfaceGeneration = generation;
            window = NULL;
        }
        pthread_mutex_unlock(&g_osm_surface_mutex);
        pojavReleaseBridgeWindow(window);
    }
    if(bundle->nativeSurface == NULL) {
        //prepare the buffer for our first render!
        osm_swap_surfaces(bundle);
    }
    osm_set_no_render_buffer(&bundle->buffer);
    osm_apply_current_ll();
    OSMesaPixelStore_p(OSMESA_Y_UP,0);
}

void osm_swap_buffers() {
    if(atomic_load_explicit(&currentBundle->state, memory_order_acquire) == STATE_RENDERER_NEW_WINDOW) {
        osm_swap_surfaces(currentBundle);
    }

    if(currentBundle->nativeSurface != NULL && !currentBundle->disable_rendering)
        if(ANativeWindow_lock(currentBundle->nativeSurface, &currentBundle->buffer, NULL) != 0)
            osm_release_window();

    osm_apply_current_ll();
    glFinish_p(); // this will force osmesa to write the last rendered image into the buffer

    if(currentBundle->nativeSurface != NULL && !currentBundle->disable_rendering)
        if(ANativeWindow_unlockAndPost(currentBundle->nativeSurface) != 0)
            osm_release_window();
}

void osm_setup_window() {
    uint64_t generation = 0;
    ANativeWindow* window = pojavAcquireBridgeWindowWithGeneration(&generation);
    pthread_mutex_lock(&g_osm_surface_mutex);
    basic_render_window_t* mainBundle = pojav_environ->mainWindowBundle;
    if(mainBundle != NULL) {
        if (generation < mainBundle->latestSurfaceGeneration) {
            pthread_mutex_unlock(&g_osm_surface_mutex);
            pojavReleaseBridgeWindow(window);
            return;
        }
        if (mainBundle->newNativeSurface != NULL) {
            pojavReleaseBridgeWindow(mainBundle->newNativeSurface);
        }
        mainBundle->newNativeSurface = window;
        mainBundle->newSurfaceGeneration = generation;
        mainBundle->latestSurfaceGeneration = generation;
        atomic_store_explicit(
            &mainBundle->state,
            STATE_RENDERER_NEW_WINDOW,
            memory_order_release
        );
        pthread_mutex_unlock(&g_osm_surface_mutex);
        return;
    }
    pthread_mutex_unlock(&g_osm_surface_mutex);
    pojavReleaseBridgeWindow(window);
    pojavAcknowledgeBridgeWindowGeneration(generation);
}

void osm_swap_interval(int swapInterval) {
    pthread_mutex_lock(&g_osm_surface_mutex);
    basic_render_window_t* mainBundle = pojav_environ->mainWindowBundle;
    ANativeWindow* window = mainBundle == NULL ? NULL : mainBundle->nativeSurface;
    if (window != NULL) {
        ANativeWindow_acquire(window);
    }
    pthread_mutex_unlock(&g_osm_surface_mutex);
    if (window != NULL) {
        setNativeWindowSwapInterval(window, swapInterval);
        pojavReleaseBridgeWindow(window);
    }
}
