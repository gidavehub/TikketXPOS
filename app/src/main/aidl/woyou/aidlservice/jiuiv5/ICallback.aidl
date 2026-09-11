/**
 * Callback interface of the Woyou/Sunmi on-device printer service.
 * Method order MUST match the service's own AIDL — binder transaction codes
 * are positional.
 */
package woyou.aidlservice.jiuiv5;

interface ICallback {
    oneway void onRunResult(boolean isSuccess);
    oneway void onReturnString(String result);
    oneway void onRaiseException(int code, String msg);
    oneway void onPrintResult(int code, String msg);
}
