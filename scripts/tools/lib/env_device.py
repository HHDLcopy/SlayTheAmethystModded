import os

_STS_TEST_DEVICE = os.environ.get("STS_TEST_DEVICE", "auto")


def get_test_device_serial() -> str:
    return _STS_TEST_DEVICE
