"""环境变量工具。

STS_TEST_DEVICE      — 默认设备序列号（默认 "auto"）
STS_CONNECTOR_PORT   — connector daemon 端口（ConnectorClient 读取）
STS_CONNECTOR_TOKEN  — connector daemon 认证 token（ConnectorClient 读取）
"""
import os

_STS_TEST_DEVICE = os.environ.get("STS_TEST_DEVICE", "auto")


def get_test_device_serial() -> str:
    return _STS_TEST_DEVICE
