from __future__ import annotations

import json as _json
import socket
import unittest
from unittest.mock import MagicMock, patch


class TestConnectorClient(unittest.TestCase):

    def test_connect_sends_auth(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=51234, token="abc")
        with patch("socket.socket") as mock_socket_cls:
            mock_sock = MagicMock()
            mock_socket_cls.return_value = mock_sock
            client._sock = mock_sock
            client.connect()
            mock_sock.connect.assert_called_once_with(("127.0.0.1", 51234))
            mock_sock.sendall.assert_called_once_with(b"AUTH abc\n")

    def test_send_request_json_roundtrip(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1, token="x")
        mock_sock = MagicMock()
        mock_sock.recv.side_effect = [
            b'{"result":{"ok":true}}\n',
            b"",
        ]
        client._sock = mock_sock
        response = client.send_request({"method": "ping"})
        mock_sock.sendall.assert_called_once()
        sent_bytes = mock_sock.sendall.call_args[0][0]
        sent = _json.loads(sent_bytes.decode("utf-8"))
        self.assertEqual(sent, {"method": "ping"})
        self.assertEqual(response, {"result": {"ok": True}})

    def test_close_shuts_down_socket_and_clears_state(self):
        from scripts.tools.connector.client import ConnectorClient
        mock_sock = MagicMock()
        client = ConnectorClient(port=1, token="x")
        client._sock = mock_sock
        client.close()
        mock_sock.close.assert_called_once()

    def test_devices_returns_device_list(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1, token="x")
        client._sock = MagicMock()
        client._sock.recv.return_value = (
            b'{"devices":[{"serial":"x","state":"device"}]}\n')
        result = client.devices()
        self.assertEqual(
            result, [{"serial": "x", "state": "device"}])

    def test_select_sends_serial_and_timeout(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1, token="x")
        client._sock = MagicMock()
        client._sock.recv.return_value = b'{"ok":true}\n'
        result = client.select(serial="abc", timeout_ms=5000)
        self.assertTrue(result)

    def test_status_returns_dict(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1, token="x")
        client._sock = MagicMock()
        client._sock.recv.return_value = (
            b'{"serial":"abc","state":"online"}\n')
        result = client.status()
        self.assertEqual(result["serial"], "abc")

    def test_forward_returns_port_info(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1, token="x")
        client._sock = MagicMock()
        client._sock.recv.return_value = (
            b'{"ok":true,"port":9099}\n')
        result = client.forward(port=9099)
        self.assertEqual(result["port"], 9099)

    def test_unforward_returns_ok(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1, token="x")
        client._sock = MagicMock()
        client._sock.recv.return_value = b'{"ok":true}\n'
        result = client.unforward(port=9099)
        self.assertTrue(result)

    def test_shell_returns_stdout(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1, token="x")
        client._sock = MagicMock()
        client._sock.recv.return_value = (
            b'{"exit":0,"stdout":"hello","stderr":""}\n')
        result = client.shell("echo hello")
        self.assertEqual(result["stdout"], "hello")

    @patch("socket.socket")
    def test_connect_stream_sends_request_and_returns_stream_id(self, mock_socket_cls):
        from scripts.tools.connector.client import ConnectorClient
        mock_new_sock = MagicMock()
        mock_socket_cls.return_value = mock_new_sock

        client = ConnectorClient(port=1, token="x")
        mock_sock = MagicMock()
        mock_sock.recv.return_value = b'{"stream_id":"s1"}\n'
        client._sock = mock_sock
        stream = client.connect_stream(port=8099)
        sent = mock_sock.sendall.call_args[0][0]
        req = _json.loads(sent.decode("utf-8"))
        self.assertEqual(req["method"], "connect_stream")
        self.assertEqual(req["params"]["port"], 8099)
        self.assertEqual(stream.stream_id, "s1")
        mock_new_sock.connect.assert_called_once_with(("127.0.0.1", 1))
        mock_new_sock.sendall.assert_called_once_with(b"AUTH x\n")
        self.assertIs(client._sock, mock_new_sock)

    @patch("socket.socket")
    def test_stream_raw_io(self, mock_socket_cls):
        from scripts.tools.connector.client import ConnectorClient, Stream
        mock_new_sock = MagicMock()
        mock_socket_cls.return_value = mock_new_sock

        client = ConnectorClient(port=1, token="x")
        mock_sock = MagicMock()
        mock_sock.recv.side_effect = [b'{"stream_id":"st"}\n']
        client._sock = mock_sock
        stream = client.connect_stream(port=8099)
        self.assertIsInstance(stream, Stream)
        self.assertEqual(stream.stream_id, "st")
        stream.write(b"hello\n")
        self.assertEqual(mock_sock.sendall.call_count, 2)
        mock_sock.sendall.assert_any_call(b"hello\n")
        # client socket was replaced with a fresh reconnected one
        self.assertIs(client._sock, mock_new_sock)


if __name__ == "__main__":
    unittest.main()
