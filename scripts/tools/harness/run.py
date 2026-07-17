from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._runner import gradle


def _gradle_device_properties(ctx: HarnessContext) -> list[str]:
    device_serial = ctx.resolved_device_serial.strip()
    if not device_serial:
        return []
    return [f"-PdeviceSerial={device_serial}"]


def run_start(ctx: HarnessContext) -> None:
    args = [
        ":app:stsStart",
        f"-PlaunchMode={ctx.options.launch_mode}",
        f"-PforceJvmCrash={str(ctx.options.force_jvm_crash).lower()}",
        f"-PforceRuntimeCrash={str(ctx.options.force_runtime_crash).lower()}",
        f"-PdebugMode={str(ctx.options.debug_mode).lower()}",
        f"-Pautoplay={str(ctx.options.autoplay).lower()}",
        f"-PautoplaySaveMode={ctx.options.autoplay_save_mode}",
        f"-PautoplayMode={ctx.options.autoplay_mode}",
        f"-PautoplaySingleRoomSpec=",
        "-PdisableCardObtainEffectOwnershipCompat="
        + str(ctx.options.disable_card_obtain_effect_ownership_compat).lower(),
        *_gradle_device_properties(ctx),
    ]
    gradle(ctx, args)
    set_result_success(ctx, True, "START_REQUESTED", "Launch request was sent through :app:stsStart.")


def run_stop(ctx: HarnessContext) -> None:
    gradle(ctx, [":app:stsStop", *_gradle_device_properties(ctx)])
    set_result_success(ctx, True, "STOPPED", "Application force-stop completed.")
