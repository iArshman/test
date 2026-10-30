import { Config } from "./types";

declare var _getImportsFunctionName: string;
declare var _runtimeName: string;
export const runtimeName = _runtimeName;

let remoteImports: any = null;
for (const moduleName of ["DeviceBridge", "Device"]) {
    try {
        const imports = require(_runtimeName + "_core/" + moduleName)?.[_getImportsFunctionName]?.();
        if (imports != null) {
            remoteImports = imports;
            break;
        }
    } catch {
        // Some Snapchat builds expose DeviceBridge, others only Device.
    }
}

function callRemoteFunction(method: string, ...args: any[]): any | null {
    try {
        const fn = remoteImports?.[method];
        if (typeof fn !== "function") return null;
        return fn(...args);
    } catch {
        return null;
    }
}


export const log = (logLevel: string, message: string) => callRemoteFunction("log", logLevel, message);

export const getConfig = () => callRemoteFunction("getConfig") as Config;

export const downloadLastOperaMedia = (isLongPress: boolean) => callRemoteFunction("downloadLastOperaMedia", isLongPress);

export function getFriendOriginalUsername(username: string): string | null {
    return callRemoteFunction("getFriendOriginalUsername", username);
}

export function setEvalFunction(func: (code: string, callback: any) => any): void {
    callRemoteFunction("setEvalFunction", func);
}
