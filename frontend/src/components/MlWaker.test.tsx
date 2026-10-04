import { act, render } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { MlWaker } from './MlWaker'

afterEach(() => vi.useRealTimers())

it('opens the health check in a hidden frame and opens it again while the service starts', () => {
  vi.useFakeTimers()
  const { container } = render(<MlWaker url="https://ml.example.test/health" />)
  const first = container.querySelector('iframe')!
  expect(first).toHaveAttribute('src', 'https://ml.example.test/health')
  expect(first).toHaveStyle({ visibility: 'hidden' })
  act(() => vi.advanceTimersByTime(30_000))
  expect(container.querySelector('iframe')).not.toBe(first) // a fresh visit, not the same frame
})

it('does nothing when no address is configured', () => {
  const { container } = render(<MlWaker url="" />)
  expect(container.querySelector('iframe')).toBeNull()
})
