import { ActionPrompt } from './action.js'
import { EventPrompt } from './event.js'

export function PromptManager() {
  return (
    <>
      <ActionPrompt />
      <EventPrompt />
    </>
  )
}

export { ActionPrompt, EventPrompt }