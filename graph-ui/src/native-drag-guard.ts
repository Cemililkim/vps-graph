export const NATIVE_DRAG_ALLOWED_SELECTOR = '[data-native-drag="allowed"]'

export function preventNativeDrag(event: Pick<DragEvent, 'target' | 'preventDefault'>): boolean {
  const target = event.target && typeof event.target === 'object' && 'closest' in event.target
    ? event.target as Element
    : typeof Node !== 'undefined' && event.target instanceof Node ? event.target.parentElement : null
  if (target?.closest(NATIVE_DRAG_ALLOWED_SELECTOR)) return false
  event.preventDefault()
  return true
}

export function installNativeDragGuard(): () => void {
  const onDragStart = (event: DragEvent) => { preventNativeDrag(event) }
  document.addEventListener('dragstart', onDragStart, true)
  return () => document.removeEventListener('dragstart', onDragStart, true)
}
