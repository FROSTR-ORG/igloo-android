import { Logger, LoggerConfig } from '@vbyte/micro-lib'

const config : LoggerConfig = {
  debug   : false,
  width   : 120,
  silent  : false,
  verbose : true
}

export const create_logger = Logger.init(config)
