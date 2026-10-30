import { defineModule } from "../types";
import { interceptComponent, proxyProperty } from "../utils";

export default defineModule({
    name: "Bypass Camera Roll Selection Limit",
    enabled: config => config.bypassCameraRollLimit,
    init() {
        try {
            interceptComponent(
                'memories_ui/src/clickhandlers/MultiSelectClickHandler',
                'MultiSelectClickHandler',
                {
                    "<init>": (args: any[], superCall: () => void) => {
                        args[1].selectionLimit = 9999999;
                        superCall();
                    }
                }
            )
        } catch (e) {}

        try {
            const { MemoriesPickerView } = require('memories/src/MemoriesPicker');
            proxyProperty(MemoriesPickerView, 'create', (target: any, thisArg: any, args: any[]) => {
                if (args[1]?.m60491i) args[1].m60491i(9999999);
                return Reflect.apply(target, thisArg, args);
            });
        } catch (e) {}
    }
});