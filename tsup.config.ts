import type { Options } from './tsup';

import { defineConfig } from 'tsup';
import { DIR_DST_GUILLOTINE } from './tsup/constants';


export default defineConfig((options: Options) => {
	if (options.d === DIR_DST_GUILLOTINE) {
		return import('./tsup/guillotine').then(m => m.default());
	}
	throw new Error(`Unconfigured directory:${options.d}!`)
})
