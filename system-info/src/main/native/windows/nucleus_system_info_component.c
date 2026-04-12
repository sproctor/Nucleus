// Temperature sensor information via WMI (MSAcpi_ThermalZoneTemperature).
// Sources: COM/WMI (IWbemLocator, IWbemServices)
//
// NOTE: MSAcpi_ThermalZoneTemperature in root\WMI typically requires
// administrator privileges. Running without elevation returns 0 sensors.

#include "nucleus_system_info_common.h"
#include <math.h>

// WMI COM interfaces
#include <objbase.h>
#include <wbemcli.h>

#define MAX_COMPONENTS 64

typedef struct {
    char label[128];
    float temperature; // Celsius, NAN if unavailable
    float max_temp;    // NAN if unavailable
    float critical;    // NAN if unavailable
} component_entry_t;

static component_entry_t g_components[MAX_COMPONENTS];
static int g_component_count = 0;

// Convert tenths-of-Kelvin to Celsius
static float kelvin_tenths_to_celsius(long val) {
    return (float)val / 10.0f - 273.15f;
}

// Query a WMI namespace for thermal data.
// ns_path: L"root\\WMI" or L"root\\cimv2"
// wql_query: the WQL SELECT query
// temp_prop / crit_prop / label_prop: property names to read
static void query_thermal(
    const wchar_t *ns_path,
    const wchar_t *wql_query,
    const wchar_t *temp_prop,
    const wchar_t *crit_prop,
    const wchar_t *label_prop)
{
    HRESULT hr;

    // Create WMI locator
    IWbemLocator *locator = NULL;
    hr = CoCreateInstance(&CLSID_WbemLocator, NULL, CLSCTX_INPROC_SERVER,
        &IID_IWbemLocator, (void **)&locator);
    if (FAILED(hr)) return;

    // Connect to namespace
    IWbemServices *services = NULL;
    BSTR ns = SysAllocString(ns_path);
    hr = locator->lpVtbl->ConnectServer(locator, ns, NULL, NULL, NULL, 0, NULL, NULL, &services);
    SysFreeString(ns);
    if (FAILED(hr)) { locator->lpVtbl->Release(locator); return; }

    // Set proxy security on this connection
    CoSetProxyBlanket((IUnknown *)services, RPC_C_AUTHN_WINNT, RPC_C_AUTHZ_NONE, NULL,
        RPC_C_AUTHN_LEVEL_CALL, RPC_C_IMP_LEVEL_IMPERSONATE, NULL, EOAC_NONE);

    // Execute query
    IEnumWbemClassObject *enumerator = NULL;
    BSTR wql = SysAllocString(L"WQL");
    BSTR query = SysAllocString(wql_query);
    hr = services->lpVtbl->ExecQuery(services, wql, query,
        WBEM_FLAG_FORWARD_ONLY | WBEM_FLAG_RETURN_IMMEDIATELY, NULL, &enumerator);
    SysFreeString(wql);
    SysFreeString(query);
    if (FAILED(hr)) goto done;

    // Iterate results
    IWbemClassObject *obj = NULL;
    ULONG returned = 0;
    while (g_component_count < MAX_COMPONENTS) {
        hr = enumerator->lpVtbl->Next(enumerator, 3000, 1, &obj, &returned);
        if (hr != S_OK || returned == 0) break;

        component_entry_t *c = &g_components[g_component_count];
        c->temperature = NAN;
        c->max_temp = NAN;
        c->critical = NAN;
        strcpy(c->label, "Thermal Zone");

        // Label
        VARIANT var;
        VariantInit(&var);
        hr = obj->lpVtbl->Get(obj, label_prop, 0, &var, NULL, NULL);
        if (SUCCEEDED(hr) && var.vt == VT_BSTR && var.bstrVal) {
            char *name = wchar_to_utf8(var.bstrVal);
            if (name && name[0]) {
                strncpy(c->label, name, sizeof(c->label) - 1);
                c->label[sizeof(c->label) - 1] = '\0';
                free(name);
            } else {
                if (name) free(name);
            }
        }
        VariantClear(&var);

        // Temperature
        VariantInit(&var);
        hr = obj->lpVtbl->Get(obj, temp_prop, 0, &var, NULL, NULL);
        if (SUCCEEDED(hr)) {
            if (var.vt == VT_I4 || var.vt == VT_UI4) {
                c->temperature = kelvin_tenths_to_celsius(var.lVal);
            } else if (var.vt == VT_R4) {
                c->temperature = var.fltVal;
            } else if (var.vt == VT_R8) {
                c->temperature = (float)var.dblVal;
            }
        }
        VariantClear(&var);

        // Critical threshold
        if (crit_prop) {
            VariantInit(&var);
            hr = obj->lpVtbl->Get(obj, crit_prop, 0, &var, NULL, NULL);
            if (SUCCEEDED(hr)) {
                if (var.vt == VT_I4 || var.vt == VT_UI4) {
                    c->critical = kelvin_tenths_to_celsius(var.lVal);
                } else if (var.vt == VT_R4) {
                    c->critical = var.fltVal;
                } else if (var.vt == VT_R8) {
                    c->critical = (float)var.dblVal;
                }
            }
            VariantClear(&var);
        }

        obj->lpVtbl->Release(obj);
        g_component_count++;
    }

    enumerator->lpVtbl->Release(enumerator);

done:
    services->lpVtbl->Release(services);
    locator->lpVtbl->Release(locator);
}

static void refresh_components(void) {
    g_component_count = 0;

    // COM init: the JVM may already have initialized COM on this thread.
    // Track whether we did it so we only uninitialize if we were the ones who init'd.
    HRESULT com_hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    int we_initialized_com = (com_hr == S_OK);
    // S_FALSE = already initialized on this thread (same model) -- fine, don't uninit
    // RPC_E_CHANGED_MODE = already initialized with different model -- fine, COM is usable
    if (FAILED(com_hr) && com_hr != RPC_E_CHANGED_MODE) return;

    // Do NOT call CoInitializeSecurity -- the JVM already set it for the process.
    // We rely on CoSetProxyBlanket per-connection instead.

    // Try root\WMI -> MSAcpi_ThermalZoneTemperature (requires admin usually)
    query_thermal(
        L"root\\WMI",
        L"SELECT * FROM MSAcpi_ThermalZoneTemperature",
        L"CurrentTemperature",
        L"CriticalTripPoint",
        L"InstanceName");

    // If nothing found, try root\cimv2 -> Win32_TemperatureProbe (rarely populated but no admin needed)
    if (g_component_count == 0) {
        query_thermal(
            L"root\\cimv2",
            L"SELECT * FROM Win32_TemperatureProbe",
            L"CurrentReading",
            NULL,
            L"Description");
    }

    if (we_initialized_com) {
        CoUninitialize();
    }
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentCount(
    JNIEnv *env, jclass clazz) {
    refresh_components();
    return g_component_count;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentLabels(
    JNIEnv *env, jclass clazz) {
    const char *labels[MAX_COMPONENTS];
    for (int i = 0; i < g_component_count; i++) labels[i] = g_components[i].label;
    return to_string_array(env, labels, g_component_count);
}

JNIEXPORT jfloatArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentTemperatures(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_component_count);
    jfloat *vals = (jfloat *)malloc(g_component_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_component_count; i++) vals[i] = g_components[i].temperature;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_component_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentMaxTemperatures(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_component_count);
    jfloat *vals = (jfloat *)malloc(g_component_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_component_count; i++) vals[i] = g_components[i].max_temp;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_component_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_io_github_kdroidfilter_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentCriticalTemperatures(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_component_count);
    jfloat *vals = (jfloat *)malloc(g_component_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_component_count; i++) vals[i] = g_components[i].critical;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_component_count, vals);
    free(vals);
    return arr;
}
