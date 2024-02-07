declare const __brand: unique symbol
declare const __base: unique symbol

type Brand<Base, B> = {
  [__brand]: B
  [__base]: Base
}

export type Branded<Base, B> = Base & Brand<Base, B>
export type BrandBase<T> = T extends Brand<infer Base, any> ? Base : never

export type BrandBuilder<T extends Branded<Base, any>, Base = BrandBase<T>> = {
	check: (value: Base) => value is T
	assert: (value: Base) => asserts value is T
	from: (value: Base) => T
}

export type BrandBuilderOptions<Base> = {
	validate?: (value: Base) => boolean | string
}
