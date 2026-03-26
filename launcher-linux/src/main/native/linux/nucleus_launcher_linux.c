/**
 * JNI bridge for Unity Launcher API via D-Bus (GIO/GDBus).
 *
 * Implements the com.canonical.Unity.LauncherEntry interface:
 * - Signal: Update (app_uri, properties)
 * - Method: Query () -> (app_uri, properties)
 *
 * Specification: https://wiki.ubuntu.com/Unity/LauncherAPI
 *
 * Dependencies: GLib/GIO (libgio-2.0)
 */

#include <jni.h>
#include <gio/gio.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>

/* ---- D-Bus constants -------------------------------------------------- */

#define LAUNCHER_INTERFACE "com.canonical.Unity.LauncherEntry"
#define LAUNCHER_OBJECT    "/com/canonical/Unity/LauncherEntry"

/* Sentinel for "boolean property not set" — matches -1 from Kotlin */
#define BOOL_NOT_SET (-1)

/* ---- D-Bus introspection XML for Query method ------------------------- */

static const gchar introspection_xml[] =
    "<node>"
    "  <interface name='com.canonical.Unity.LauncherEntry'>"
    "    <method name='Query'>"
    "      <arg type='s' name='app_uri' direction='out'/>"
    "      <arg type='a{sv}' name='properties' direction='out'/>"
    "    </method>"
    "    <signal name='Update'>"
    "      <arg type='s' name='app_uri'/>"
    "      <arg type='a{sv}' name='properties'/>"
    "    </signal>"
    "  </interface>"
    "</node>";

/* ---- Global state ----------------------------------------------------- */

static JavaVM *g_jvm = NULL;

/* Shared D-Bus connection */
static GDBusConnection *g_conn = NULL;

/* Query handler state */
static pthread_mutex_t g_state_mutex = PTHREAD_MUTEX_INITIALIZER;
static guint           g_registration_id = 0;
static GDBusNodeInfo  *g_node_info = NULL;

/* Current state for Query responses */
static char   *g_app_uri          = NULL;
static int     g_has_count        = 0;
static gint64  g_count            = 0;
static int     g_count_visible    = BOOL_NOT_SET;
static int     g_has_progress     = 0;
static gdouble g_progress         = 0.0;
static int     g_progress_visible = BOOL_NOT_SET;
static int     g_urgent           = BOOL_NOT_SET;
static char   *g_quicklist        = NULL;
static int     g_updating         = BOOL_NOT_SET;

/* GMainLoop for Query handler */
static GMainLoop *g_query_loop   = NULL;
static pthread_t  g_query_thread;
static volatile int g_query_running = 0;

/* ---- JNI lifecycle ---------------------------------------------------- */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

/* ---- D-Bus connection ------------------------------------------------- */

static GDBusConnection *get_connection(void) {
    if (g_conn != NULL && !g_dbus_connection_is_closed(g_conn)) {
        return g_conn;
    }
    if (g_conn != NULL) {
        g_object_unref(g_conn);
        g_conn = NULL;
    }
    GError *error = NULL;
    g_conn = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (error != NULL) {
        g_error_free(error);
        g_conn = NULL;
    }
    return g_conn;
}

/* ---- Helper: build properties variant from current state -------------- */

static GVariant *build_properties_variant(
    int has_count, gint64 count, int count_visible,
    int has_progress, gdouble progress, int progress_visible,
    int urgent, const char *quicklist, int updating)
{
    GVariantBuilder builder;
    g_variant_builder_init(&builder, G_VARIANT_TYPE("a{sv}"));

    if (has_count) {
        g_variant_builder_add(&builder, "{sv}", "count",
            g_variant_new_int64(count));
    }

    if (count_visible != BOOL_NOT_SET) {
        g_variant_builder_add(&builder, "{sv}", "count-visible",
            g_variant_new_boolean(count_visible ? TRUE : FALSE));
    }

    if (has_progress) {
        g_variant_builder_add(&builder, "{sv}", "progress",
            g_variant_new_double(progress));
    }

    if (progress_visible != BOOL_NOT_SET) {
        g_variant_builder_add(&builder, "{sv}", "progress-visible",
            g_variant_new_boolean(progress_visible ? TRUE : FALSE));
    }

    if (urgent != BOOL_NOT_SET) {
        g_variant_builder_add(&builder, "{sv}", "urgent",
            g_variant_new_boolean(urgent ? TRUE : FALSE));
    }

    if (quicklist != NULL) {
        g_variant_builder_add(&builder, "{sv}", "quicklist",
            g_variant_new_string(quicklist));
    }

    if (updating != BOOL_NOT_SET) {
        g_variant_builder_add(&builder, "{sv}", "updating",
            g_variant_new_boolean(updating ? TRUE : FALSE));
    }

    return g_variant_builder_end(&builder);
}

/* ==== Update signal ==================================================== */

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_linux_NativeLinuxLauncherBridge_nativeUpdate(
    JNIEnv *env, jclass clazz,
    jstring j_app_uri,
    jboolean has_count,
    jlong count,
    jint count_visible,
    jboolean has_progress,
    jdouble progress,
    jint progress_visible,
    jint urgent,
    jstring j_quicklist,
    jint updating)
{
    (void)clazz;

    GDBusConnection *conn = get_connection();
    if (conn == NULL) return JNI_FALSE;

    const char *app_uri = (*env)->GetStringUTFChars(env, j_app_uri, NULL);
    if (app_uri == NULL) return JNI_FALSE;

    const char *quicklist = NULL;
    if (j_quicklist != NULL) {
        quicklist = (*env)->GetStringUTFChars(env, j_quicklist, NULL);
    }

    GVariant *properties = build_properties_variant(
        has_count, (gint64)count, (int)count_visible,
        has_progress, (gdouble)progress, (int)progress_visible,
        (int)urgent, quicklist, (int)updating);

    GVariant *params = g_variant_new("(s@a{sv})", app_uri, properties);

    GError *error = NULL;
    g_dbus_connection_emit_signal(conn,
        NULL,                /* destination (broadcast) */
        LAUNCHER_OBJECT,     /* object path */
        LAUNCHER_INTERFACE,  /* interface */
        "Update",            /* signal name */
        params,
        &error);

    /* Also update internal state for Query */
    pthread_mutex_lock(&g_state_mutex);
    g_free(g_app_uri);
    g_app_uri = g_strdup(app_uri);
    if (has_count) { g_has_count = 1; g_count = (gint64)count; }
    if (count_visible != BOOL_NOT_SET) g_count_visible = (int)count_visible;
    if (has_progress) { g_has_progress = 1; g_progress = (gdouble)progress; }
    if (progress_visible != BOOL_NOT_SET) g_progress_visible = (int)progress_visible;
    if (urgent != BOOL_NOT_SET) g_urgent = (int)urgent;
    if (quicklist != NULL) {
        g_free(g_quicklist);
        g_quicklist = g_strdup(quicklist);
    }
    if (updating != BOOL_NOT_SET) g_updating = (int)updating;
    pthread_mutex_unlock(&g_state_mutex);

    (*env)->ReleaseStringUTFChars(env, j_app_uri, app_uri);
    if (j_quicklist != NULL && quicklist != NULL) {
        (*env)->ReleaseStringUTFChars(env, j_quicklist, quicklist);
    }

    if (error != NULL) {
        g_error_free(error);
        return JNI_FALSE;
    }

    /* Flush to ensure the signal is sent immediately */
    g_dbus_connection_flush_sync(conn, NULL, NULL);

    return JNI_TRUE;
}

/* ==== Query method handler ============================================= */

static void handle_method_call(
    GDBusConnection *conn,
    const gchar *sender,
    const gchar *object_path,
    const gchar *interface_name,
    const gchar *method_name,
    GVariant *parameters,
    GDBusMethodInvocation *invocation,
    gpointer user_data)
{
    (void)conn; (void)sender; (void)object_path;
    (void)interface_name; (void)parameters; (void)user_data;

    if (g_strcmp0(method_name, "Query") != 0) {
        g_dbus_method_invocation_return_error(invocation,
            G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_METHOD,
            "Unknown method: %s", method_name);
        return;
    }

    pthread_mutex_lock(&g_state_mutex);

    const char *uri = g_app_uri ? g_app_uri : "";
    GVariant *properties = build_properties_variant(
        g_has_count, g_count, g_count_visible,
        g_has_progress, g_progress, g_progress_visible,
        g_urgent, g_quicklist, g_updating);

    GVariant *result = g_variant_new("(s@a{sv})", uri, properties);
    pthread_mutex_unlock(&g_state_mutex);

    g_dbus_method_invocation_return_value(invocation, result);
}

static const GDBusInterfaceVTable vtable = {
    handle_method_call,
    NULL,   /* get_property */
    NULL,   /* set_property */
    {0}
};

/* Query handler thread */
static void *query_thread_func(void *arg) {
    (void)arg;

    GMainContext *ctx = g_main_context_new();
    g_main_context_push_thread_default(ctx);

    GMainLoop *loop = g_main_loop_new(ctx, FALSE);

    pthread_mutex_lock(&g_state_mutex);
    g_query_loop = loop;
    pthread_mutex_unlock(&g_state_mutex);

    g_main_loop_run(loop);

    pthread_mutex_lock(&g_state_mutex);
    g_query_loop = NULL;
    g_query_running = 0;
    pthread_mutex_unlock(&g_state_mutex);

    g_main_loop_unref(loop);
    g_main_context_pop_thread_default(ctx);
    g_main_context_unref(ctx);

    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_linux_NativeLinuxLauncherBridge_nativeRegisterQueryHandler(
    JNIEnv *env, jclass clazz,
    jstring j_app_uri)
{
    (void)clazz;

    GDBusConnection *conn = get_connection();
    if (conn == NULL) return JNI_FALSE;

    const char *app_uri = (*env)->GetStringUTFChars(env, j_app_uri, NULL);
    if (app_uri == NULL) return JNI_FALSE;

    pthread_mutex_lock(&g_state_mutex);

    /* Store app URI for Query responses */
    g_free(g_app_uri);
    g_app_uri = g_strdup(app_uri);

    /* Parse introspection XML */
    if (g_node_info == NULL) {
        GError *error = NULL;
        g_node_info = g_dbus_node_info_new_for_xml(introspection_xml, &error);
        if (error != NULL) {
            g_error_free(error);
            pthread_mutex_unlock(&g_state_mutex);
            (*env)->ReleaseStringUTFChars(env, j_app_uri, app_uri);
            return JNI_FALSE;
        }
    }

    /* Register the object on the bus */
    if (g_registration_id == 0) {
        GError *error = NULL;
        g_registration_id = g_dbus_connection_register_object(conn,
            LAUNCHER_OBJECT,
            g_node_info->interfaces[0],
            &vtable,
            NULL,  /* user_data */
            NULL,  /* user_data_free_func */
            &error);

        if (error != NULL) {
            g_error_free(error);
            pthread_mutex_unlock(&g_state_mutex);
            (*env)->ReleaseStringUTFChars(env, j_app_uri, app_uri);
            return JNI_FALSE;
        }
    }

    /* Start the GMainLoop thread for handling incoming method calls */
    if (!g_query_running) {
        g_query_running = 1;
        if (pthread_create(&g_query_thread, NULL, query_thread_func, NULL) != 0) {
            g_query_running = 0;
            pthread_mutex_unlock(&g_state_mutex);
            (*env)->ReleaseStringUTFChars(env, j_app_uri, app_uri);
            return JNI_FALSE;
        }
    }

    pthread_mutex_unlock(&g_state_mutex);
    (*env)->ReleaseStringUTFChars(env, j_app_uri, app_uri);

    return JNI_TRUE;
}

/* ==== SetState (update internal state without emitting signal) ========== */

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_linux_NativeLinuxLauncherBridge_nativeSetState(
    JNIEnv *env, jclass clazz,
    jboolean has_count,
    jlong count,
    jint count_visible,
    jboolean has_progress,
    jdouble progress,
    jint progress_visible,
    jint urgent,
    jstring j_quicklist,
    jint updating)
{
    (void)clazz;

    const char *quicklist = NULL;
    if (j_quicklist != NULL) {
        quicklist = (*env)->GetStringUTFChars(env, j_quicklist, NULL);
    }

    pthread_mutex_lock(&g_state_mutex);
    if (has_count) { g_has_count = 1; g_count = (gint64)count; }
    if (count_visible != BOOL_NOT_SET) g_count_visible = (int)count_visible;
    if (has_progress) { g_has_progress = 1; g_progress = (gdouble)progress; }
    if (progress_visible != BOOL_NOT_SET) g_progress_visible = (int)progress_visible;
    if (urgent != BOOL_NOT_SET) g_urgent = (int)urgent;
    if (quicklist != NULL) {
        g_free(g_quicklist);
        g_quicklist = g_strdup(quicklist);
    }
    if (updating != BOOL_NOT_SET) g_updating = (int)updating;
    pthread_mutex_unlock(&g_state_mutex);

    if (j_quicklist != NULL && quicklist != NULL) {
        (*env)->ReleaseStringUTFChars(env, j_quicklist, quicklist);
    }
}

/* ==== Unregister ======================================================= */

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_launcher_linux_NativeLinuxLauncherBridge_nativeUnregister(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    pthread_mutex_lock(&g_state_mutex);

    /* Stop the query handler thread */
    if (g_query_running && g_query_loop != NULL) {
        g_main_loop_quit(g_query_loop);
        pthread_mutex_unlock(&g_state_mutex);
        pthread_join(g_query_thread, NULL);
        pthread_mutex_lock(&g_state_mutex);
    }

    /* Unregister the D-Bus object */
    if (g_registration_id != 0 && g_conn != NULL) {
        g_dbus_connection_unregister_object(g_conn, g_registration_id);
        g_registration_id = 0;
    }

    /* Free introspection data */
    if (g_node_info != NULL) {
        g_dbus_node_info_unref(g_node_info);
        g_node_info = NULL;
    }

    /* Free state */
    g_free(g_app_uri);
    g_app_uri = NULL;
    g_free(g_quicklist);
    g_quicklist = NULL;

    /* Reset state */
    g_has_count = 0;
    g_count = 0;
    g_count_visible = BOOL_NOT_SET;
    g_has_progress = 0;
    g_progress = 0.0;
    g_progress_visible = BOOL_NOT_SET;
    g_urgent = BOOL_NOT_SET;
    g_updating = BOOL_NOT_SET;

    pthread_mutex_unlock(&g_state_mutex);
}
