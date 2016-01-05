import * as net from "net"
import { JsonRpc, Transport } from "./rpc"

export class RpcClient {
  connect(port: number = 63342) {
    const socket = net.connect({port: port}, () => {
      console.log("Connected to IJ RPC server localhost: " + port)
    })

    const transport = new SocketTransport(socket)
    const jsonRpc = new JsonRpc(transport)
    const decoder = new MessageDecoder(jsonRpc.messageReceived)
    socket.on("data", decoder.messageReceived)
  }
}

const enum State {LENGTH, CONTENT}

export class SocketTransport implements Transport {
  private headerBuffer = new Buffer(4)

  opened: () => void

  constructor(private socket: net.Socket = new net.Socket()) {
  }

  connect(port: number = 63342) {
    this.socket.connect(port, null, ()=> {
      const opened = this.opened
      if (opened != null) {
        opened()
      }
    })
    this.socket.on("error", (e: Error) => {
      console.error(e)
    })
    this.socket.write(new Buffer([67, 72, 105, -107, 126, -21, -81, -72, 64, 54, -87, -88, 0, -46, -48, 34, -7, -67]))
  }

  send(id: number, domain: string, command: string, params: any[] = null): void {
    const encodedParams = JSON.stringify(params)
    const header = (id == -1 ? '' : (id + ', ')) + '"' + domain + '", "' + command + '"';
    this.headerBuffer.writeUInt32BE(header.length + Buffer.byteLength(encodedParams), 0)
    this.socket.write(this.headerBuffer)
    this.socket.write(header)
    this.socket.write(encodedParams)
  }

  sendResult(id: number, result: any): void {
    this.sendResultOrError(id, result, false)
  }

  sendError(id: number, error: any): void {
    this.sendResultOrError(id, error, true)
  }

  private sendResultOrError(id: number, result: any, isError: boolean): void {
    var encodedResult = JSON.stringify(result)
    var header = id + ', "' + (isError ? 'e' : 'r') + '"';
    this.headerBuffer.writeUInt32BE(Buffer.byteLength(encodedResult) + header.length, 0)
    this.socket.write(this.headerBuffer)
    this.socket.write(encodedResult)
  }
}

class MessageDecoder {
  private state: State = State.LENGTH
  private contentLength: number = 0

  private buffers: Array<Buffer> = []
  private totalBufferLength: number = 0
  private offset: number = 0

  constructor(private messageProcessor: (message: any)=>void) {
  }

  private byteConsumed(count: number) {
    this.offset += count
    this.totalBufferLength -= count
  }

  messageReceived(buffer: Buffer) {
    this.totalBufferLength += buffer.length

    while (true) {
      //noinspection FallThroughInSwitchStatementJS
      switch (this.state) {
        case State.LENGTH:
        {
          if (this.totalBufferLength < 4) {
            this.buffers.push(buffer)
            return
          }

          var totalBuffer: Buffer
          if (this.buffers.length === 0) {
            totalBuffer = buffer
          }
          else {
            this.buffers.push(buffer)
            totalBuffer = Buffer.concat(this.buffers, this.totalBufferLength)
            this.buffers.length = 0
          }

          this.state = State.CONTENT
          this.contentLength = totalBuffer.readUInt32BE(this.offset)
          this.byteConsumed(4)
          buffer = totalBuffer
        }

        case State.CONTENT:
        {
          if (this.totalBufferLength < this.contentLength) {
            this.buffers.push(buffer)
            return
          }

          var totalBuffer: Buffer
          if (this.buffers.length === 0) {
            totalBuffer = buffer
          }
          else {
            this.buffers.push(buffer)
            totalBuffer = Buffer.concat(this.buffers, this.totalBufferLength)
            this.buffers.length = 0
          }

          var message = JSON.parse(totalBuffer.toString('utf8', this.offset, this.contentLength));
          this.state = State.LENGTH
          this.byteConsumed(this.contentLength)
          this.contentLength = 0
          buffer = totalBuffer

          this.messageProcessor(message)
        }
      }
    }
  }
}